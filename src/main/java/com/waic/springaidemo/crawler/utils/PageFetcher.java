package com.waic.springaidemo.crawler.utils;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Component;

import java.util.function.Predicate;

/**
 * 页面抓取器，优先使用 Jsoup，失败时使用 Playwright 兜底
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PageFetcher {

    private final PlaywrightManager playwrightManager;

    /**
     * 抓取页面并解析为 Document（无验证，直接返回）
     *
     * @param url 目标 URL
     * @return Document
     */
    public Document fetchDocument(String url) {
        return fetchDocument(url, null);
    }

    /**
     * 抓取页面并解析为 Document，通过 validator 验证内容有效性。
     * 若 Jsoup 请求成功但 validator 校验失败（JS 渲染导致的空壳页面），
     * 会自动降级到 Playwright 重新抓取。
     *
     * @param url       目标 URL
     * @param validator 内容验证器，返回 false 时将触发 Playwright 兜底
     * @return Document
     */
    public Document fetchDocument(String url, Predicate<Document> validator) {
        try {
            Document doc = JsoupUtils.fetchDocument(url);
            if (validator != null && !validator.test(doc)) {
                throw new IllegalStateException("Document validation failed, content does not match expected structure");
            }
            return doc;
        } catch (Exception e) {
            log.warn("Jsoup fetch failed/validation failed for {}, fallback to Playwright", url, e);
            try {
                String html = playwrightManager.fetchHtml(url);
                return Jsoup.parse(html, url);
            } catch (Exception playwrightException) {
                log.error("Playwright fallback also failed for {}", url, playwrightException);
                throw new RuntimeException("All fetch methods failed for URL: " + url, playwrightException);
            }
        }
    }

    /**
     * 直接使用 Playwright 抓取页面，跳过 Jsoup 尝试（适用于已知需要 JS 渲染的网站）
     *
     * @param url 目标 URL
     * @return Document
     */
    public Document fetchDocumentWithPlaywright(String url) {
        return fetchDocumentWithPlaywright(url, null);
    }

    /**
     * 直接使用 Playwright 抓取页面，并可等待指定选择器出现后再取内容。
     *
     * @param url            目标 URL
     * @param waitForSelector 需等待出现的 CSS 选择器，为 null 时退化为等待 load 事件
     * @return Document
     */
    public Document fetchDocumentWithPlaywright(String url, String waitForSelector) {
        log.info("Using Playwright directly for {} (waitForSelector={})", url, waitForSelector);
        String html = playwrightManager.fetchHtml(url, waitForSelector);
        return Jsoup.parse(html, url);
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
            try {
                return playwrightManager.fetchHtml(url);
            } catch (Exception playwrightException) {
                log.error("Playwright fallback also failed for {}", url, playwrightException);
                throw new RuntimeException("All fetch methods failed for URL: " + url, playwrightException);
            }
        }
    }
}
