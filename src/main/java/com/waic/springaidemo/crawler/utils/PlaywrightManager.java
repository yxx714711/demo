package com.waic.springaidemo.crawler.utils;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Playwright 管理器，启动时初始化浏览器并在应用关闭时释放资源。
 */
@Slf4j
@Component
public class PlaywrightManager {

    private Playwright playwright;
    private Browser browser;

    /**
     * 应用启动时初始化 Playwright 浏览器。
     * 初始化失败会抛出异常，阻断 Spring 容器启动，便于快速发现部署/依赖问题。
     */
    @PostConstruct
    public synchronized void initialize() {
        if (playwright != null) {
            return;
        }
        log.info("Initializing Playwright chromium browser at startup...");
        try {
            playwright = Playwright.create();
            browser = playwright.chromium().launch();
            log.info("Playwright chromium browser initialized successfully");
        } catch (Exception e) {
            log.error("Failed to initialize Playwright browser at startup", e);
            throw new IllegalStateException("Failed to initialize Playwright browser at startup", e);
        }
    }

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
        if (browser == null) {
            throw new IllegalStateException("Playwright browser is not initialized");
        }
        try (Page page = browser.newPage()) {
            page.navigate(url);
            if (waitForSelector != null && !waitForSelector.isBlank()) {
                // 默认超时 30s；显式等待目标选择器出现，
                // 避免 SPA 在 load 事件后异步渲染导致拿到空壳页面。
                page.waitForSelector(waitForSelector);
            } else {
                page.waitForLoadState();
            }
            return page.content();
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
