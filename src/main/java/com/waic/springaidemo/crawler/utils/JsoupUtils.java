package com.waic.springaidemo.crawler.utils;

import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.io.IOException;

/**
 * Jsoup 工具类
 */
@Slf4j
public final class JsoupUtils {

    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";
    private static final int TIMEOUT_MILLIS = 30000;

    private JsoupUtils() {
    }

    /**
     * 抓取页面并解析为 Document
     *
     * @param url 目标 URL
     * @return Document
     */
    public static Document fetchDocument(String url) {
        try {
            return Jsoup.connect(url)
                    .userAgent(USER_AGENT)
                    .timeout(TIMEOUT_MILLIS)
                    .followRedirects(true)
                    .get();
        } catch (IOException e) {
            log.error("Failed to fetch document from url: {}", url, e);
            throw new IllegalStateException("Failed to fetch document from url: " + url, e);
        }
    }

    /**
     * 抓取页面原始 HTML 字符串
     *
     * @param url 目标 URL
     * @return HTML 字符串
     */
    public static String fetchHtml(String url) {
        try {
            return Jsoup.connect(url)
                    .userAgent(USER_AGENT)
                    .timeout(TIMEOUT_MILLIS)
                    .followRedirects(true)
                    .execute()
                    .body();
        } catch (IOException e) {
            log.error("Failed to fetch html from url: {}", url, e);
            throw new IllegalStateException("Failed to fetch html from url: " + url, e);
        }
    }
}
