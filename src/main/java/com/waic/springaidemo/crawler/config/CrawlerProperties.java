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
        private TopNConfig topN = new TopNConfig();
    }

    @Data
    public static class GiteeConfig {
        private List<String> categories = new ArrayList<>();
        private List<String> languages = new ArrayList<>();
        private TopNConfig topN = new TopNConfig();
    }

    @Data
    public static class JuejinConfig {
        private List<String> categories = new ArrayList<>();
        private TopNConfig topN = new TopNConfig();
    }

    /**
     * 各周期 TopN 配置，默认值均为 10
     */
    @Data
    public static class TopNConfig {
        private Integer daily = 10;
        private Integer weekly = 10;
        private Integer monthly = 10;
    }
}
