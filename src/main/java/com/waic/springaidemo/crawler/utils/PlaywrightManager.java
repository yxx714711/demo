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
        ensureInitialized();
        try (Page page = browser.newPage()) {
            page.navigate(url);
            page.waitForLoadState();
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
