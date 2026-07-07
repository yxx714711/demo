package com.waic.springaidemo.crawler.utils;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Component;

/**
 * 页面抓取器，优先使用 Jsoup，失败时使用 Playwright 兜底
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PageFetcher {

    private final PlaywrightManager playwrightManager;

    /**
     * 抓取页面并解析为 Document
     *
     * @param url 目标 URL
     * @return Document
     */
    public Document fetchDocument(String url) {
        try {
            return JsoupUtils.fetchDocument(url);
        } catch (Exception e) {
            log.warn("Jsoup fetch failed for {}, fallback to Playwright", url, e);
            String html = playwrightManager.fetchHtml(url);
            return Jsoup.parse(html, url);
        }
    }

    /**
     * 抓取页面原始 HTML 字符串
     *
     * @param url 目标 URL
     * @return HTML 字符串
     */
    public String fetchHtml(String url) {
        try {
            return JsoupUtils.fetchHtml(url);
        } catch (Exception e) {
            log.warn("Jsoup fetch failed for {}, fallback to Playwright", url, e);
            return playwrightManager.fetchHtml(url);
        }
    }
}
