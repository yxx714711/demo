package com.waic.springaidemo.crawler.utils;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Playwright 管理器，用于 Jsoup 抓取失败时的兜底渲染
 */
@Slf4j
@Component
public class PlaywrightManager {

    private Playwright playwright;
    private Browser browser;

    /**
     * 使用 Playwright 获取页面完整 HTML
     *
     * @param url 目标 URL
     * @return 页面 HTML
     */
    public synchronized String fetchHtml(String url) {
        return fetchHtml(url, null);
    }

    /**
     * 使用 Playwright 获取页面完整 HTML。
     * 若指定 waitForSelector，则等待该选择器出现后再取内容（适用于 JS/SPA 站点，
     * 避免 waitForLoadState 在内容异步渲染完成前就返回空壳）。
     *
     * @param url            目标 URL
     * @param waitForSelector 需等待出现的 CSS 选择器，为 null 或空白时退化为等待 load 事件
     * @return 页面 HTML
     */
    public synchronized String fetchHtml(String url, String waitForSelector) {
        ensureInitialized();
        try (Page page = browser.newPage()) {
            page.navigate(url);
            if (waitForSelector != null && !waitForSelector.isBlank()) {
                // 默认超时 30s，与 Jsoup 超时保持一致；显式等待目标选择器出现，
                // 避免 SPA 在 load 事件后异步渲染导致拿到空壳页面。
                page.waitForSelector(waitForSelector);
            } else {
                page.waitForLoadState();
            }
            return page.content();
        }
    }

    private synchronized void ensureInitialized() {
        if (playwright == null) {
            log.info("Initializing Playwright chromium browser...");
            playwright = Playwright.create();
            browser = playwright.chromium().launch();
        }
    }

    @PreDestroy
    public synchronized void destroy() {
        log.info("Closing Playwright resources");
        if (browser != null) {
            browser.close();
        }
        if (playwright != null) {
            playwright.close();
        }
    }
}
