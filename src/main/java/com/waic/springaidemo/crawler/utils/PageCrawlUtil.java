package com.waic.springaidemo.crawler.utils;

import com.waic.springaidemo.crawler.components.BrowserManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Component;

import java.util.function.Predicate;

/**
 * 页面抓取器，统一使用浏览器获取真实 HTML，再用 Jsoup 解析。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PageCrawlUtil {

    private final BrowserManager browserManager;

    /**
     * 抓取页面并解析为 Document。
     *
     * @param url 目标 URL
     * @return Document
     */
    public Document crawlDocument(String url) {
        return crawlDocument(url, null, null);
    }

    /**
     * 抓取页面并解析为 Document，可等待指定选择器出现后再取内容，并通过 validator 校验。
     *
     * @param url             目标 URL
     * @param waitForSelector 需等待出现的 CSS 选择器，为 null 或空白时退化为等待 load 事件
     * @param validator       内容验证器，为 null 时不校验
     * @return Document
     */
    public Document crawlDocument(String url, String waitForSelector, Predicate<Document> validator) {
        log.info("Fetching document via browser for {} (waitForSelector={})", url, waitForSelector);
        String html = browserManager.withPage(page -> {
            page.navigate(url);
            if (waitForSelector != null && !waitForSelector.isBlank()) {
                // 默认超时 30s；显式等待目标选择器出现，
                // 避免 SPA 在 load 事件后异步渲染导致拿到空壳页面。
                page.waitForSelector(waitForSelector);
            } else {
                page.waitForLoadState();
            }
            return page.content();
        });
        Document document = Jsoup.parse(html, url);
        if (validator != null && !validator.test(document)) {
            throw new IllegalStateException("Document validation failed for URL: " + url);
        }
        return document;
    }

}
