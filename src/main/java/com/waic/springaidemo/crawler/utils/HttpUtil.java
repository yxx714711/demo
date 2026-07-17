package com.waic.springaidemo.crawler.utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * HTTP 请求工具类，统一持有可复用的 HttpClient 单例，消除各 Crawler 中重复的
 * 客户端构建、请求组装与 InterruptedException 样板处理。
 *
 * <p>提供两种重定向策略：
 * <ul>
 *     <li>{@link #getFollow}：自动跟随重定向，用于 raw 资源抓取；</li>
 *     <li>{@link #getNoFollow}：不跟随重定向，由调用方手动处理 301/302（如 GitHub API）。</li>
 * </ul>
 *
 * <p>默认注入 {@code User-Agent}，调用方可通过 headers 传参覆盖（调用方优先）。
 * 方法统一返回 {@link HttpResponse}，状态码/响应头判定交由业务层处理。
 */
@Slf4j
@Component
public class HttpUtil {

    private static final String DEFAULT_USER_AGENT = "Mozilla/5.0";
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient followClient;
    private final HttpClient noFollowClient;

    public HttpUtil() {
        this.followClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(DEFAULT_TIMEOUT)
                .build();
        this.noFollowClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(DEFAULT_TIMEOUT)
                .build();
    }

    /**
     * 自动跟随重定向的 GET 请求。
     *
     * @param url     目标 URL，非空
     * @param headers 额外请求头，可为 null；其中 {@code User-Agent} 可覆盖默认值
     * @return 完整响应
     */
    public HttpResponse<String> getFollow(String url, Map<String, String> headers) throws IOException {
        return send(followClient, url, headers);
    }

    /**
     * 不跟随重定向的 GET 请求，便于调用方手动处理 3xx。
     *
     * @param url     目标 URL，非空
     * @param headers 额外请求头，可为 null；其中 {@code User-Agent} 可覆盖默认值
     * @return 完整响应
     */
    public HttpResponse<String> getNoFollow(String url, Map<String, String> headers) throws IOException {
        return send(noFollowClient, url, headers);
    }

    private HttpResponse<String> send(HttpClient client, String url, Map<String, String> headers) throws IOException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(DEFAULT_TIMEOUT)
                .GET();
        if (headers != null) {
            headers.forEach((k, v) -> {
                if (v != null) {
                    builder.header(k, v);
                }
            });
        }
        // 默认 UA 仅在调用方未指定时补上，允许调用方覆盖
        if (headers == null || !headers.containsKey("User-Agent")) {
            builder.header("User-Agent", DEFAULT_USER_AGENT);
        }
        try {
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while requesting " + url, e);
        }
    }
}
