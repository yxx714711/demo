package com.waic.springaidemo.crawler.service.impl;

import com.waic.springaidemo.crawler.config.CrawlerProperties;
import com.waic.springaidemo.common.entity.FetchRequest;
import com.waic.springaidemo.common.entity.FetchResult;
import com.waic.springaidemo.common.entity.HotItem;
import com.waic.springaidemo.common.enums.DataSourceEnum;
import com.waic.springaidemo.common.enums.PeriodEnum;
import com.waic.springaidemo.common.utils.FilePathUtils;
import com.waic.springaidemo.crawler.service.Crawler;
import com.waic.springaidemo.crawler.utils.PageFetcherUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

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
 * @author 10542
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GithubCrawler implements Crawler {

    private static final String TRENDING_URL = "https://github.com/trending/%s?since=%s";
    private static final String API_README_URL = "https://api.github.com/repos/%s/%s/readme";
    private static final String USER_AGENT = "Mozilla/5.0";
    private static final int[] RETRYABLE_STATUS = {429, 500, 502, 503, 504};

    private final CrawlerProperties crawlerProperties;
    private final PageFetcherUtil pageFetcherUtil;
    private final HttpClient httpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
    // API 调用不自动跟随重定向，以便手动处理 302 到大文件 download_url 的情况
    private final HttpClient apiClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public DataSourceEnum getDataSource() {
        return DataSourceEnum.GITHUB;
    }

    @Override
    public List<PeriodEnum> getSupportedPeriods() {
        return Arrays.asList(PeriodEnum.DAILY, PeriodEnum.WEEKLY, PeriodEnum.MONTHLY);
    }

    @Override
    public List<String> getCategories() {
        return new ArrayList<>();
    }

    @Override
    public List<String> getLanguages() {
        List<String> languages = crawlerProperties.getGithub().getLanguages();
        return CollectionUtils.isEmpty(languages) ? List.of("all") : languages;
    }

    @Override
    public FetchResult crawl(FetchRequest context) {
        String periodParam = mapPeriod(context.getPeriod());
        String langParam = "all".equals(context.getLanguage()) ? "" : context.getLanguage();
        String url = String.format(TRENDING_URL, langParam, periodParam);
        log.info("正在抓取 GitHub 热门仓库: {}", url);

        Document document = pageFetcherUtil.fetchDocument(url, "article.Box-row",
                doc -> !doc.select("article.Box-row").isEmpty());
        Elements rows = document.select("article.Box-row");
        if (rows.isEmpty()) {
            log.warn("未找到热门项目，URL: {}", url);
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

    private HotItem parseRow(Element row, FetchRequest context) {
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
    public void download(HotItem item, FetchResult result) throws IOException {
        String repoPath = item.getUrl().replace("https://github.com/", "");
        String[] parts = repoPath.split("/");
        if (parts.length < 2) {
            throw new IOException("Invalid repo path: " + item.getUrl());
        }
        String owner = parts[0];
        String repo = parts[1];

        if (tryDownloadViaApi(owner, repo, item, result)) {
            return;
        }
        throw new IOException("Failed to download README for " + item.getTitle()
                + " (API failed): " + item.getUrl());
    }

    /**
     * 主路径：GitHub Contents API，自动解析默认分支与 README 文件名。
     * 200 且 content 非空 -> base64 解码写入；302 或 content 为空但有 download_url -> 直接取该 raw 地址。
     * 429/5xx/网络异常 -> 重试；404/400/403(非限流) -> 直接结束。
     */
    private boolean tryDownloadViaApi(String owner, String repo, HotItem item, FetchResult result) throws IOException {
        String url = String.format(API_README_URL, owner, repo);
        int maxRetries = 3;
        int attempt = 0;
        while (true) {
            try {
                HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                        .header("User-Agent", USER_AGENT)
                        .header("Accept", "application/vnd.github+json")
                        .GET();
                HttpResponse<String> response = apiClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
                int code = response.statusCode();
                if (code == 200) {
                    String content = extractApiContent(response.body());
                    if (content != null) {
                        saveContent(content, item, result);
                        return true;
                    }
                    String downloadUrl = extractApiDownloadUrl(response.body());
                    if (downloadUrl != null) {
                        return fetchRawAndSave(downloadUrl, item, result);
                    }
                    return false;
                }
                if (code == 301 || code == 302) {
                    String location = response.headers().firstValue("Location").orElse(null);
                    if (location != null) {
                        return fetchRawAndSave(location, item, result);
                    }
                    return false;
                }
                if (isRetryable(code, response)) {
                    if (attempt < maxRetries) {
                        attempt++;
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
                    continue;
                }
                return false;
            }
        }
    }

    /**
     * 对给定 raw 地址做一次 GET 并尝试保存（用于 API 重定向 / download_url 场景，不再重试）
     */
    private boolean fetchRawAndSave(String url, HotItem item, FetchResult result) throws IOException {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", USER_AGENT)
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                saveContent(response.body(), item, result);
                return true;
            }
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while fetching " + url, e);
        }
    }

    private void saveContent(String content, HotItem item, FetchResult result) throws IOException {
        String repoPath = item.getUrl().replace("https://github.com/", "").replace("/", "_");
        Path contentFilePath = FilePathUtils.getContentFilePath(result.getSource(), result.getPeriod(),
                result.getDate(), result.getCategory(), result.getLanguage(), repoPath);
        Files.createDirectories(contentFilePath.getParent());
        Files.writeString(contentFilePath, content);
        item.setContentPath(contentFilePath.toString().replace("\\", "/"));
        log.info("已下载 README：{}，保存至 {}", item.getTitle(), item.getContentPath());
    }

    private String extractApiContent(String body) {
        try {
            JsonNode node = objectMapper.readTree(body);
            JsonNode contentNode = node.get("content");
            if (contentNode == null || contentNode.isNull()) {
                return null;
            }
            String content = contentNode.asString("");
            if (content.isBlank()) {
                return null;
            }
            content = content.replaceAll("\\s+", "");
            return new String(Base64.getDecoder().decode(content), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("解析 GitHub API README 内容失败", e);
            return null;
        }
    }

    private String extractApiDownloadUrl(String body) {
        try {
            JsonNode node = objectMapper.readTree(body);
            JsonNode downloadUrl = node.get("download_url");
            if (downloadUrl != null && !downloadUrl.isNull()) {
                return downloadUrl.asString("");
            }
        } catch (Exception e) {
            log.warn("解析 GitHub API download_url 失败", e);
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
