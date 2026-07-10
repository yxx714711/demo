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
import java.util.function.Predicate;

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
        return crawl(date, period, crawler -> true, null);
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
        return crawl(date, period, crawler -> crawler.supports(probe),
                "No crawler supports source=" + source + ", period=" + period);
    }

    /**
     * 通用抓取方法：对通过 crawlerFilter 的抓取器，遍历其 buildContexts 产出的上下文并执行抓取。
     * buildContexts 已保证只返回受支持的上下文，故此处不再逐个做 supports 过滤。
     * 单个上下文抓取失败时跳过并继续，不影响其他上下文。
     *
     * @param date           日期
     * @param period         周期
     * @param crawlerFilter  抓取器过滤器，决定哪些抓取器参与本次抓取
     * @param noMatchMessage 当没有任何抓取器通过过滤时抛出的异常信息；为 null 表示不抛异常
     * @return 抓取结果列表
     */
    private List<FetchResult> crawl(LocalDate date, PeriodEnum period,
                                    Predicate<Crawler> crawlerFilter, String noMatchMessage) {
        List<FetchResult> results = new ArrayList<>();
        boolean anyMatched = false;
        for (Crawler crawler : crawlers) {
            if (!crawlerFilter.test(crawler)) {
                continue;
            }
            anyMatched = true;
            for (CrawlerContext context : crawler.buildContexts(date, period)) {
                try {
                    results.add(crawler.crawl(context));
                } catch (Exception e) {
                    log.warn("Crawl failed for {} context={}, skipping",
                            crawler.getClass().getSimpleName(), context, e);
                }
            }
        }
        if (noMatchMessage != null && !anyMatched) {
            throw new IllegalStateException(noMatchMessage);
        }
        return results;
    }
}
