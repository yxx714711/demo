package com.waic.springaidemo.crawler.service.impl;

import com.waic.springaidemo.crawler.config.CrawlerProperties;
import com.waic.springaidemo.common.entity.FetchResult;
import com.waic.springaidemo.common.entity.HotItem;
import com.waic.springaidemo.common.enums.DataSourceEnum;
import com.waic.springaidemo.common.enums.PeriodEnum;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.waic.springaidemo.common.utils.FilePathUtils;
import com.waic.springaidemo.crawler.entity.CrawlerContext;
import com.waic.springaidemo.crawler.utils.PageFetcher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * GitHub 抓取器
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GithubCrawler extends AbstractCrawler {

    private static final String TRENDING_URL = "https://github.com/trending/%s?since=%s";
    private static final String API_README_URL = "https://api.github.com/repos/%s/%s/readme";
    private static final String RAW_README_URL = "https://raw.githubusercontent.com/%s/%s/%s/README.md";
    private static final String USER_AGENT = "Mozilla/5.0";
    private static final List<String> SUPPORTED_README_BRANCHES = Arrays.asList("main", "master");
    private static final int[] RETRYABLE_STATUS = {429, 500, 502, 503, 504};

    private final CrawlerProperties crawlerProperties;
    private final PageFetcher pageFetcher;
    private final HttpClient httpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
    // API 调用不自动跟随重定向，以便手动处理 302 到大文件 download_url 的情况
    private final HttpClient apiClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    protected DataSourceEnum getDataSource() {
        return DataSourceEnum.GITHUB;
    }

    @Override
    protected List<PeriodEnum> getSupportedPeriods() {
        return Arrays.asList(PeriodEnum.DAILY, PeriodEnum.WEEKLY, PeriodEnum.MONTHLY);
    }

    @Override
    protected List<String> getCategories() {
        return new ArrayList<>();
    }

    @Override
    protected List<String> getLanguages() {
        List<String> languages = crawlerProperties.getGithub().getLanguages();
        return CollectionUtils.isEmpty(languages) ? List.of("all") : languages;
    }

    @Override
    public FetchResult crawl(CrawlerContext context) {
        String periodParam = mapPeriod(context.getPeriod());
        String langParam = "all".equals(context.getLanguage()) ? "" : context.getLanguage();
        String url = String.format(TRENDING_URL, langParam, periodParam);
        log.info("Crawling GitHub trending: {}", url);

        Document document = pageFetcher.fetchDocument(url, doc -> !doc.select("article.Box-row").isEmpty());
        Elements rows = document.select("article.Box-row");
        if (rows.isEmpty()) {
            log.warn("No trending items found for url: {}", url);
        }

        List<HotItem> items = new ArrayList<>();
        int topN = resolveTopN(context.getPeriod());
        int count = 0;
        for (Element row : rows) {
            if (count >= topN) {
                break;
            }
            HotItem item = parseRow(row, context);
            if (item == null) {
                continue;
            }
            items.add(item);
            count++;
        }

        return FetchResult.builder()
                .source(context.getSource())
                .period(context.getPeriod())
                .date(context.getDate())
                .category(context.getCategory())
                .language(context.getLanguage())
                .items(items)
                .build();
    }

    private HotItem parseRow(Element row, CrawlerContext context) {
        Element linkElement = row.selectFirst("h2 a");
        if (linkElement == null) {
            return null;
        }
        String relativeUrl = linkElement.attr("href");
        if (relativeUrl.isBlank()) {
            return null;
        }
        String fullUrl = "https://github.com" + relativeUrl;
        String title = linkElement.text().trim().replaceAll("\\s+", " ");
        String description = "";
        Element descElement = row.selectFirst("p.color-fg-muted");
        if (descElement != null) {
            description = descElement.text().trim();
        }

        String repoPath = relativeUrl.startsWith("/") ? relativeUrl.substring(1) : relativeUrl;
        return HotItem.builder()
                .id("github_" + repoPath.replace("/", "_"))
                .title(title)
                .url(fullUrl)
                .source(context.getSource())
                .period(context.getPeriod())
                .category(context.getCategory())
                .language(context.getLanguage())
                .summary(description)
                .fetchedAt(LocalDateTime.now())
                .build();
    }

    @Override
    public void download(HotItem item, CrawlerContext context) throws IOException {
        // 不同 item 之间的节流，降低被 GitHub 限流的概率
        sleepThrottle();

        String repoPath = item.getUrl().replace("https://github.com/", "");
        String[] parts = repoPath.split("/");
        if (parts.length < 2) {
            throw new IOException("Invalid repo path: " + item.getUrl());
        }
        String owner = parts[0];
        String repo = parts[1];

        if (tryDownloadViaApi(owner, repo, item, context)) {
            return;
        }
        log.warn("GitHub API failed for {}/{}, falling back to raw URL", owner, repo);

        if (tryDownloadViaRaw(owner, repo, item, context)) {
            return;
        }
        throw new IOException("Failed to download README for " + item.getTitle()
                + " (API and raw both failed): " + item.getUrl());
    }

    /**
     * 主路径：GitHub Contents API，自动解析默认分支与 README 文件名。
     * 200 且 content 非空 -> base64 解码写入；302 或 content 为空但有 download_url -> 直接取该 raw 地址。
     * 429/5xx/网络异常 -> 指数退避重试；404/400/403(非限流) -> 直接降级到 raw。
     */
    private boolean tryDownloadViaApi(String owner, String repo, HotItem item, CrawlerContext context) throws IOException {
        String url = String.format(API_README_URL, owner, repo);
        int maxRetries = getApiRetryMax();
        int attempt = 0;
        while (true) {
            try {
                HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                        .header("User-Agent", USER_AGENT)
                        .header("Accept", "application/vnd.github+json")
                        .GET();
                String token = crawlerProperties.getGithub().getToken();
                if (token != null && !token.isBlank()) {
                    builder.header("Authorization", "Bearer " + token);
                }
                HttpResponse<String> response = apiClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
                int code = response.statusCode();
                if (code == 200) {
                    String content = extractApiContent(response.body());
                    if (content != null) {
                        saveContent(content, item, context);
                        return true;
                    }
                    String downloadUrl = extractApiDownloadUrl(response.body());
                    if (downloadUrl != null) {
                        return fetchRawAndSave(downloadUrl, item, context);
                    }
                    return false;
                }
                if (code == 301 || code == 302) {
                    String location = response.headers().firstValue("Location").orElse(null);
                    if (location != null) {
                        return fetchRawAndSave(location, item, context);
                    }
                    return false;
                }
                if (isRetryable(code, response)) {
                    if (attempt < maxRetries) {
                        attempt++;
                        sleepBackoff(response, attempt);
                        continue;
                    }
                    return false;
                }
                return false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while calling GitHub API for " + url, e);
            } catch (IOException e) {
                if (attempt < maxRetries) {
                    attempt++;
                    sleepBackoff(null, attempt);
                    continue;
                }
                return false;
            }
        }
    }

    /**
     * 兜底路径：raw.githubusercontent.com，按分支顺序尝试 README.md。
     * 429/5xx/网络异常 -> 退避重试；404 等非可重试错误 -> 立即尝试下一分支，不消耗退避。
     */
    private boolean tryDownloadViaRaw(String owner, String repo, HotItem item, CrawlerContext context) throws IOException {
        int maxRetries = getApiRetryMax();
        for (String branch : SUPPORTED_README_BRANCHES) {
            String url = String.format(RAW_README_URL, owner, repo, branch);
            int attempt = 0;
            while (true) {
                try {
                    HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                            .header("User-Agent", USER_AGENT)
                            .GET()
                            .build();
                    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                    int code = response.statusCode();
                    if (code == 200) {
                        saveContent(response.body(), item, context);
                        return true;
                    }
                    if (isRetryable(code, response)) {
                        if (attempt < maxRetries) {
                            attempt++;
                            sleepBackoff(response, attempt);
                            continue;
                        }
                        break;
                    }
                    break;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while downloading README from " + url, e);
                } catch (IOException e) {
                    if (attempt < maxRetries) {
                        attempt++;
                        sleepBackoff(null, attempt);
                        continue;
                    }
                    break;
                }
            }
        }
        return false;
    }

    /**
     * 对给定 raw 地址做一次 GET 并尝试保存（用于 API 重定向 / download_url 场景，不再重试）
     */
    private boolean fetchRawAndSave(String url, HotItem item, CrawlerContext context) throws IOException {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", USER_AGENT)
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                saveContent(response.body(), item, context);
                return true;
            }
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while fetching " + url, e);
        }
    }

    private void saveContent(String content, HotItem item, CrawlerContext context) throws IOException {
        String repoPath = item.getUrl().replace("https://github.com/", "").replace("/", "_");
        Path contentFilePath = FilePathUtils.getContentFilePath(context.getSource(), context.getPeriod(),
                context.getDate(), context.getCategory(), context.getLanguage(), repoPath);
        Files.createDirectories(contentFilePath.getParent());
        Files.writeString(contentFilePath, content);
        item.setContentPath(contentFilePath.toString().replace("\\", "/"));
        log.info("Downloaded README for {} to {}", item.getTitle(), item.getContentPath());
    }

    private String extractApiContent(String body) {
        try {
            JsonNode node = objectMapper.readTree(body);
            JsonNode contentNode = node.get("content");
            if (contentNode == null || contentNode.isNull()) {
                return null;
            }
            String content = contentNode.asText();
            if (content.isBlank()) {
                return null;
            }
            content = content.replaceAll("\\s+", "");
            return new String(Base64.getDecoder().decode(content), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("Failed to parse GitHub API README content", e);
            return null;
        }
    }

    private String extractApiDownloadUrl(String body) {
        try {
            JsonNode node = objectMapper.readTree(body);
            JsonNode downloadUrl = node.get("download_url");
            if (downloadUrl != null && !downloadUrl.isNull()) {
                return downloadUrl.asText();
            }
        } catch (Exception e) {
            log.warn("Failed to parse GitHub API download_url", e);
        }
        return null;
    }

    private boolean isRetryable(int code, HttpResponse<String> response) {
        for (int retryable : RETRYABLE_STATUS) {
            if (code == retryable) {
                return true;
            }
        }
        if (code == 403) {
            // 限流类 403 会带 Retry-After 头
            return response != null && response.headers().firstValue("Retry-After").isPresent();
        }
        return false;
    }

    private void sleepBackoff(HttpResponse<String> response, int attempt) throws IOException {
        long delayMs = getApiRetryBaseMs();
        if (response != null) {
            String retryAfter = response.headers().firstValue("Retry-After").orElse(null);
            if (retryAfter != null) {
                try {
                    long secs = Long.parseLong(retryAfter.trim());
                    delayMs = Math.max(delayMs, secs * 1000);
                } catch (NumberFormatException ignored) {
                    // 非数字格式（如 HTTP 日期）时忽略，沿用基础退避
                }
            }
        }
        log.warn("GitHub rate limited, backing off {}ms before retry #{}", delayMs, attempt);
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted during backoff", e);
        }
    }

    private void sleepThrottle() {
        long ms = getThrottleMs();
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private int getApiRetryMax() {
        Integer v = crawlerProperties.getGithub().getApiRetryMax();
        return v == null ? 3 : v;
    }

    private long getApiRetryBaseMs() {
        Integer v = crawlerProperties.getGithub().getApiRetryBaseMs();
        return v == null ? 30000L : v;
    }

    private long getThrottleMs() {
        Integer v = crawlerProperties.getGithub().getThrottleMs();
        return v == null ? 800L : v;
    }

    private String mapPeriod(PeriodEnum period) {
        return switch (period) {
            case DAILY -> "daily";
            case WEEKLY -> "weekly";
            case MONTHLY -> "monthly";
        };
    }

    private int resolveTopN(PeriodEnum period) {
        CrawlerProperties.GithubConfig config = crawlerProperties.getGithub();
        return switch (period) {
            case DAILY -> config.getDailyTopN();
            case WEEKLY -> config.getWeeklyTopN();
            case MONTHLY -> config.getMonthlyTopN();
        };
    }
}
