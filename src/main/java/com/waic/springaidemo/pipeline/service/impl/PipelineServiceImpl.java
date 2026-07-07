package com.waic.springaidemo.pipeline.service.impl;

import com.waic.springaidemo.ai.service.ReportGenerator;
import com.waic.springaidemo.common.entity.FetchResult;
import com.waic.springaidemo.common.entity.HotItem;
import com.waic.springaidemo.common.enums.DataSourceEnum;
import com.waic.springaidemo.common.enums.PeriodEnum;
import com.waic.springaidemo.crawler.entity.CrawlerContext;
import com.waic.springaidemo.crawler.service.Crawler;
import com.waic.springaidemo.crawler.service.CrawlerRegistry;
import com.waic.springaidemo.persistence.service.PersistenceService;
import com.waic.springaidemo.pipeline.service.PipelineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Pipeline 编排服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PipelineServiceImpl implements PipelineService {

    private final CrawlerRegistry crawlerRegistry;
    private final PersistenceService persistenceService;
    private final ReportGenerator reportGenerator;

    @Override
    public List<FetchResult> runCrawl(LocalDate date, PeriodEnum period) throws IOException {
        log.info("Running crawl pipeline for date={}, period={}", date, period);

        // Step 1: 抓取元数据（不下载内容）
        List<FetchResult> results = crawlerRegistry.crawlAll(date, period);

        // Step 2: 设置默认 contentPath 并写入 JSON
        for (FetchResult result : results) {
            for (HotItem item : result.getItems()) {
                item.setContentPath("PENDING");
            }
            persistenceService.save(result);
        }
        log.info("Saved {} fetch results with PENDING contentPath", results.size());

        // Step 3: 逐条下载内容并更新 JSON
        for (FetchResult result : results) {
            CrawlerContext downloadContext = buildDownloadContext(result);
            Crawler crawler = crawlerRegistry.resolve(downloadContext);
            for (HotItem item : result.getItems()) {
                try {
                    crawler.download(item, downloadContext);
                } catch (IOException e) {
                    log.warn("Download failed for item {} ({}): {}", item.getId(), item.getTitle(), e.getMessage());
                    // contentPath 保持 "PENDING"，供后续重试
                }
            }
            persistenceService.updateItems(result);
        }

        log.info("Crawl pipeline completed, processed {} results", results.size());
        return results;
    }

    private CrawlerContext buildDownloadContext(FetchResult result) {
        return CrawlerContext.builder()
                .source(result.getSource())
                .period(result.getPeriod())
                .date(result.getDate())
                .category(result.getCategory())
                .language(result.getLanguage())
                .build();
    }

    @Override
    public String generateDailyReport(LocalDate date) throws IOException {
        log.info("Generating daily report for date={}", date);
        List<FetchResult> allResults = new ArrayList<>();
        for (DataSourceEnum source : DataSourceEnum.values()) {
            List<FetchResult> results = persistenceService.loadByDate(source, PeriodEnum.DAILY, date);
            allResults.addAll(results);
        }
        if (allResults.isEmpty()) {
            log.warn("No daily data found for date={}", date);
            throw new IllegalStateException("No daily data found for date: " + date);
        }
        String reportContent = reportGenerator.generateDailyReport(date, allResults);
        return persistenceService.saveReport("daily", date, reportContent);
    }
}
