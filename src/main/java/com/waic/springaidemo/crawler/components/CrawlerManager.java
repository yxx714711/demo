package com.waic.springaidemo.crawler.components;

import com.waic.springaidemo.common.entity.CrawlCoordinate;
import com.waic.springaidemo.crawler.service.Crawler;
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
public class CrawlerManager {

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
     * 根据抓取坐标查找支持的抓取器（列表侧）。
     * 无匹配时返回 empty，由调用方决定是优雅跳过还是抛异常。
     *
     * @param coordinate 抓取坐标
     * @return 抓取器（可能为 empty）
     */
    public Optional<Crawler> getCrawlerByCoordinate(CrawlCoordinate coordinate) {
        for (Crawler crawler : crawlers) {
            if (crawler.supports(coordinate)) {
                return Optional.of(crawler);
            }
        }
        return Optional.empty();
    }
}
