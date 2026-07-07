package com.waic.springaidemo.crawler.service;

import com.waic.springaidemo.common.entity.FetchResult;
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
}
