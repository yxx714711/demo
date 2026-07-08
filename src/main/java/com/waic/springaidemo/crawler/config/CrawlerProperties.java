package com.waic.springaidemo.crawler.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 抓取配置属性
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.crawler")
public class CrawlerProperties {

    /**
     * GitHub 配置
     */
    private GithubConfig github = new GithubConfig();

    /**
     * Gitee 配置
     */
    private GiteeConfig gitee = new GiteeConfig();

    /**
     * 掘金配置
     */
    private JuejinConfig juejin = new JuejinConfig();

    @Data
    public static class GithubConfig {
        private List<String> languages = new ArrayList<>();
        private Integer dailyTopN = 10;
        private Integer weeklyTopN = 10;
        private Integer monthlyTopN = 10;

        /**
         * GitHub PAT（可选）。不配置则走匿名 API（60 次/小时/IP），超限自动降级到 raw 兜底。
         * 建议通过环境变量注入，例如 app.crawler.github.token: ${GITHUB_TOKEN:}
         */
        private String token;

        /**
         * API / raw 下载失败时的重试次数（针对 429/5xx 等可重试错误）
         */
        private Integer apiRetryMax = 3;

        /**
         * 可重试错误的基础退避时间（毫秒），恒定退避，不翻倍。尊重 Retry-After 头时取 max(该值, Retry-After)
         */
        private Integer apiRetryBaseMs = 30000;

        /**
         * 每个 item 下载前的节流延时（毫秒），避免被 GitHub 限流。<=0 表示不节流
         */
        private Integer throttleMs = 800;
    }

    @Data
    public static class GiteeConfig {
        private List<String> categories = new ArrayList<>();
        private List<String> languages = new ArrayList<>();
        private Integer dailyTopN = 10;
    }

    @Data
    public static class JuejinConfig {
        private List<String> categories = new ArrayList<>();
        private Integer dailyTopN = 10;
    }
}
