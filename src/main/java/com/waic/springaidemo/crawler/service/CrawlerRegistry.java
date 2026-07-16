package com.waic.springaidemo.crawler.service;

import com.waic.springaidemo.common.entity.FetchRequest;
import com.waic.springaidemo.common.entity.FetchResult;
import com.waic.springaidemo.common.enums.DataSourceEnum;
import com.waic.springaidemo.common.enums.PeriodEnum;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * 抓取器注册中心（纯定位器）：持有全部 Crawler 并负责按请求/结果定位。
 * 抓取与下载的编排由上层 pipeline 完成，本类不再执行 crawl。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CrawlerRegistry {

    private final List<Crawler> crawlers;

    /**
     * 返回全部已注册抓取器（只读）。供上层遍历 buildContexts / crawl 使用。
     *
     * @return 抓取器列表（不可修改）
     */
    public List<Crawler> getAllCrawlers() {
        return Collections.unmodifiableList(crawlers);
    }

    /**
     * 根据请求查找支持的抓取器（列表侧）。
     * 无匹配时返回 empty，由调用方决定是优雅跳过还是抛异常。
     *
     * @param request 抓取请求
     * @return 抓取器（可能为 empty）
     */
    public Optional<Crawler> resolve(FetchRequest request) {
        for (Crawler crawler : crawlers) {
            if (crawler.supports(request)) {
                return Optional.of(crawler);
            }
        }
        return Optional.empty();
    }

    /**
     * 根据已落盘的抓取结果反查对应的抓取器（用于正文下载）。
     * 内部以结果重建 FetchRequest 后复用 {@link #resolve(FetchRequest)} 的定位逻辑。
     * 若无可下载的数据源（如纯列表源）返回 empty，由调用方优雅跳过。
     *
     * @param result 抓取结果
     * @return 抓取器（可能为 empty）
     */
    public Optional<Crawler> resolve(FetchResult result) {
        FetchRequest request = FetchRequest.builder()
                .source(result.getSource())
                .period(result.getPeriod())
                .date(result.getDate())
                .category(result.getCategory())
                .language(result.getLanguage())
                .build();
        return resolve(request);
    }
}
