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

    /**
     * 反爬配置：涵盖无头隐身与有头人工过反爬两类能力
     */
    private AntiBotConfig antiBot = new AntiBotConfig();

    @Data
    public static class GithubConfig {
        private List<String> languages = new ArrayList<>();
        private TopNConfig topN = new TopNConfig();
        private String hotBaseUrl = "https://github.com/trending/%s?since=%s";
        private String contentBaseUrl = "https://raw.githubusercontent.com/%s/%s/%s/README.md";
    }

    @Data
    public static class GiteeConfig {
        private List<String> categories = new ArrayList<>();
        private List<String> languages = new ArrayList<>();
        private TopNConfig topN = new TopNConfig();
        private String hotBaseUrl = "https://gitee.com/explore/%s?lang=%s&type=hot";
        private String contentBaseUrl = "https://gitee.com/%s/%s/raw/%s/README.md";
    }

    @Data
    public static class JuejinConfig {
        private List<String> categories = new ArrayList<>();
        private TopNConfig topN = new TopNConfig();
        private String hotBaseUrl = "https://juejin.cn/hot/articles";
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

    /**
     * 反爬配置：涵盖"无头隐身"与"有头人工过反爬"两类能力。
     */
    @Data
    public static class AntiBotConfig {
        /**
         * 是否允许在 waitForSelector 超时且响应 2xx 时，切换有头浏览器人工过反爬。
         * 默认关闭；无显示器的服务器环境开启会导致有头浏览器启动失败。
         */
        private boolean headfulFallbackEnabled = false;

        /**
         * 有头浏览器等待目标选择器出现的超时（毫秒），默认 120s，给人过点留时间。
         */
        private long waitTimeoutMs = 120_000L;

        /**
         * 登录态持久化文件路径（可选）。配置后：
         * 1) 启动有头浏览器时若文件已存在则加载其中 cookie/localStorage，实现免重复登录；
         * 2) 每次人工抓取结束后回写最新登录态，使登录态在应用重启后仍可复用。
         * 留空则不落盘，仅在同一次运行内复用上下文内的登录态。
         */
        private String storageStatePath;

        /**
         * 无头隐身配置：抹除自动化/指纹痕迹，让无头抓取更像真人。默认内置工业标准伪装参数，零配置即可生效。
         */
        private StealthConfig stealth = new StealthConfig();

        /**
         * 无头隐身参数。默认内置一套与 Windows 桌面端 Chrome 自洽的伪装值，亦可按环境微调。
         */
        @Data
        public static class StealthConfig {
            /**
             * 无头路径是否启用隐身。无头是主抓取路径，默认开启。
             */
            private boolean enabled = true;

            /**
             * 有头人工路径是否也套用隐身。默认关闭，避免注入脚本干扰人工操作或触发站点反注入校验。
             */
            private boolean headfulEnabled = false;

            /**
             * 真实 Chrome UA（去除 HeadlessChrome 字样）。默认内置较新版本，可按需升级。
             */
            private String userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

            /**
             * 语言，与抓取目标/代理保持一致。
             */
            private String locale = "zh-CN";

            /**
             * 时区，与 locale 保持一致。
             */
            private String timezoneId = "Asia/Shanghai";

            /**
             * 视口宽度，默认 1920。
             */
            private int viewportWidth = 1920;

            /**
             * 视口高度，默认 1080。
             */
            private int viewportHeight = 1080;

            /**
             * 色彩方案，默认 light。
             */
            private String colorScheme = "light";
        }
    }
}
