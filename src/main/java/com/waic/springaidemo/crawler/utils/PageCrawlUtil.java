package com.waic.springaidemo.crawler.utils;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.TimeoutError;
import com.waic.springaidemo.crawler.components.BrowserManager;
import com.waic.springaidemo.crawler.config.CrawlerProperties;
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
    private final CrawlerProperties crawlerProperties;

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
     * <p>当配置了人工过反爬（{@code app.crawler.anti-bot.headful-fallback-enabled=true}）且满足：
     * 传了 waitForSelector、waitForSelector 超时、主文档响应为 2xx 时，判定对方反爬生效，
     * 切换有头浏览器人工过点；人过点后 SPA 渲染出目标选择器，waitForSelector 等到即继续。
     *
     * @param url             目标 URL
     * @param waitForSelector 需等待出现的 CSS 选择器，为 null 或空白时退化为等待 load 事件
     * @param validator       内容验证器，为 null 时不校验
     * @return Document
     */
    public Document crawlDocument(String url, String waitForSelector, Predicate<Document> validator) {
        log.info("Fetching document via browser for {} (waitForSelector={})", url, waitForSelector);

        CrawlerProperties.AntiBotConfig antiBot = crawlerProperties.getAntiBot();

        // 无头首抓：捕获主文档响应状态码，并精确识别 waitForSelector 超时。
        int[] statusHolder = new int[1];
        TimeoutError[] timeoutEx = new TimeoutError[1];
        String html = null;
        try {
            html = browserManager.withPage(page -> {
                Response resp = page.navigate(url);
                statusHolder[0] = resp == null ? -1 : resp.status();
                if (waitForSelector != null && !waitForSelector.isBlank()) {
                    // 显式超时 30s 等待目标选择器出现，避免 SPA 异步渲染拿到空壳页面。
                    page.waitForSelector(waitForSelector,
                            new Page.WaitForSelectorOptions().setTimeout(30_000));
                } else {
                    page.waitForLoadState();
                }
                return page.content();
            });
        } catch (TimeoutError te) {
            // 仅捕获"等待选择器超时"；其余 PlaywrightException（导航失败等）原样向上抛。
            timeoutEx[0] = te;
        }

        boolean timedOut = timeoutEx[0] != null;
        boolean canManual = antiBot.isHeadfulFallbackEnabled()
                && waitForSelector != null && !waitForSelector.isBlank()
                && statusHolder[0] >= 200 && statusHolder[0] < 300;

        if (timedOut) {
            if (canManual) {
                // 超时且响应 2xx：判定对方反爬生效，切换有头浏览器人工过点。
                log.warn("waitForSelector timed out with 2xx (status={}) for {}, "
                        + "switching to headful browser for manual anti-bot verification", statusHolder[0], url);
                long headfulTimeout = antiBot.getWaitTimeoutMs();
                html = browserManager.withHeadfulPage(page -> {
                    page.navigate(url);
                    page.waitForSelector(waitForSelector,
                            new Page.WaitForSelectorOptions().setTimeout(headfulTimeout));
                    return page.content();
                });
            } else {
                // 超时但不满足人工条件（未开启/无选择器/非 2xx）：保持原语义抛出异常。
                throw timeoutEx[0];
            }
        }

        Document document = Jsoup.parse(html, url);
        if (validator != null && !validator.test(document)) {
            throw new IllegalStateException("Document validation failed for URL: " + url);
        }
        return document;
    }
}
