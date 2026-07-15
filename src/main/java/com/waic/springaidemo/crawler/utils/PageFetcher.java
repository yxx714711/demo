package com.waic.springaidemo.crawler.utils;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Component;

import java.util.function.Predicate;

/**
 * 页面抓取器，统一使用 Playwright 获取真实 HTML，再用 Jsoup 解析。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PageFetcher {

    private final PlaywrightManager playwrightManager;

    /**
     * 抓取页面并解析为 Document。
     *
     * @param url 目标 URL
     * @return Document
     */
    public Document fetchDocument(String url) {
        return fetchDocument(url, null, null);
    }

    /**
     * 抓取页面并解析为 Document，通过 validator 验证内容有效性。
     *
     * @param url       目标 URL
     * @param validator 内容验证器，返回 false 时抛出异常
     * @return Document
     */
    public Document fetchDocument(String url, Predicate<Document> validator) {
        return fetchDocument(url, null, validator);
    }

    /**
     * 抓取页面并解析为 Document，可等待指定选择器出现后再取内容，并通过 validator 校验。
     *
     * @param url             目标 URL
     * @param waitForSelector 需等待出现的 CSS 选择器，为 null 或空白时退化为等待 load 事件
     * @param validator       内容验证器，为 null 时不校验
     * @return Document
     */
    public Document fetchDocument(String url, String waitForSelector, Predicate<Document> validator) {
        log.info("Fetching document via Playwright for {} (waitForSelector={})", url, waitForSelector);
        String html = playwrightManager.fetchHtml(url, waitForSelector);
        Document document = Jsoup.parse(html, url);
        if (validator != null && !validator.test(document)) {
            throw new IllegalStateException("Document validation failed for URL: " + url);
        }
        return document;
    }

}
