package com.waic.springaidemo.crawler.service;

import com.waic.springaidemo.common.entity.FetchResult;
import com.waic.springaidemo.common.enums.DataSourceEnum;
import com.waic.springaidemo.common.enums.PeriodEnum;
import com.waic.springaidemo.crawler.entity.CrawlerContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 抓取器注册中心
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CrawlerRegistry {

    private final List<Crawler> crawlers;

    /**
     * 根据上下文获取支持的抓取器
     *
     * @param context 抓取上下文
     * @return 抓取器
     */
    public Crawler resolve(CrawlerContext context) {
        for (Crawler crawler : crawlers) {
            if (crawler.supports(context)) {
                return crawler;
            }
        }
        throw new IllegalStateException("No crawler supports context: " + context);
    }

    /**
     * 执行指定日期和周期下的所有抓取任务
     *
     * @param date   日期
     * @param period 周期
     * @return 所有抓取结果
     */
    public List<FetchResult> crawlAll(LocalDate date, PeriodEnum period) {
        List<FetchResult> results = new ArrayList<>();
        for (Crawler crawler : crawlers) {
            List<CrawlerContext> contexts = crawler.buildContexts(date, period);
            for (CrawlerContext context : contexts) {
                if (!crawler.supports(context)) {
                    continue;
                }
                try {
                    FetchResult result = crawler.crawl(context);
                    results.add(result);
                } catch (Exception e) {
                    log.warn("Crawl failed for {} context={}, skipping", crawler.getClass().getSimpleName(), context, e);
                }
            }
        }
        return results;
    }

    /**
     * 抓取指定数据源、日期、周期下的所有上下文。
     * 若没有任何 crawler 支持该 (source, period) 组合，抛出 IllegalStateException；
     * 单个上下文抓取失败时会跳过该上下文并继续，不影响其他上下文。
     *
     * @param source 数据源
     * @param date   日期
     * @param period 周期
     * @return 抓取结果列表
     */
    public List<FetchResult> crawlBySource(DataSourceEnum source, LocalDate date, PeriodEnum period) {
        CrawlerContext probe = CrawlerContext.builder()
                .source(source)
                .period(period)
                .date(date)
                .build();

        List<FetchResult> results = new ArrayList<>();
        boolean anySupported = false;
        for (Crawler crawler : crawlers) {
            if (!crawler.supports(probe)) {
                continue;
            }
            anySupported = true;
            for (CrawlerContext context : crawler.buildContexts(date, period)) {
                if (!crawler.supports(context)) {
                    continue;
                }
                try {
                    results.add(crawler.crawl(context));
                } catch (Exception e) {
                    log.warn("Crawl failed for {} context={}, skipping",
                            crawler.getClass().getSimpleName(), context, e);
                }
            }
        }

        if (!anySupported) {
            throw new IllegalStateException("No crawler supports source=" + source + ", period=" + period);
        }
        return results;
    }
}
