package com.waic.springaidemo.crawler.components;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.function.Function;

/**
 * 浏览器管理器，仅负责 Playwright/Browser 的创建、销毁与页面生命周期维护。
 * 具体的抓取逻辑（导航、等待、取内容）由调用方通过 {@link #withPage(Function)} 提供。
 */
@Slf4j
@Component
public class BrowserManager {

    private Playwright playwright;
    private Browser browser;

    /**
     * 应用启动时初始化 Playwright 浏览器。
     * 初始化失败会抛出异常，阻断 Spring 容器启动，便于快速发现部署/依赖问题。
     */
    @PostConstruct
    public void initialize() {
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
     * 打开一个新页面供 action 使用，action 执行完毕后自动关闭页面。
     * 页面的创建与销毁完全收口在本方法内，调用方只需关注"拿到 page 后做什么"。
     *
     * @param action 在页面上执行的操作，返回抓取结果
     * @param <T>    结果类型
     * @return action 的执行结果
     */
    public <T> T withPage(Function<Page, T> action) {
        if (browser == null) {
            throw new IllegalStateException("Playwright browser is not initialized");
        }
        try (Page page = browser.newPage()) {
            return action.apply(page);
        }
    }

    @PreDestroy
    public void destroy() {
        log.info("Closing Playwright resources");
        if (browser != null) {
            browser.close();
        }
        if (playwright != null) {
            playwright.close();
        }
    }
}
