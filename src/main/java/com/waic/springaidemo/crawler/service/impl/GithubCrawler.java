package com.waic.springaidemo.crawler.service.impl;

import com.waic.springaidemo.crawler.config.CrawlerProperties;
import com.waic.springaidemo.common.entity.FetchCoordinate;
import com.waic.springaidemo.common.entity.FetchResult;
import com.waic.springaidemo.common.entity.HotItem;
import com.waic.springaidemo.common.enums.DataSourceEnum;
import com.waic.springaidemo.common.enums.PeriodEnum;
import com.waic.springaidemo.common.utils.FilePathUtils;
import com.waic.springaidemo.crawler.service.Crawler;
import com.waic.springaidemo.crawler.utils.HttpUtil;
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
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * GitHub 抓取器
 * @author 10542
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GithubCrawler implements Crawler {

    private final CrawlerProperties crawlerProperties;
    private final PageFetcherUtil pageFetcherUtil;
    private final HttpUtil httpUtil;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public DataSourceEnum getDataSource() {
        return DataSourceEnum.GITHUB;
    }

    @Override
    public List<PeriodEnum> getPeriods() {
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
    public FetchResult crawl(FetchCoordinate coordinate) {
        String periodParam = getPeriod(coordinate.period());
        String langParam = "all".equals(coordinate.language()) ? "" : coordinate.language();
        String url = String.format(crawlerProperties.getGithub().getHotBaseUrl(), langParam, periodParam);
        log.info("正在抓取 GitHub 热门仓库: {}", url);

        Document document = pageFetcherUtil.fetchDocument(url, "article.Box-row",
                doc -> !doc.select("article.Box-row").isEmpty());
        Elements rows = document.select("article.Box-row");
        if (rows.isEmpty()) {
            log.warn("未找到热门项目，URL: {}", url);
        }

        List<HotItem> items = new ArrayList<>();
        int topN = getTopN(coordinate.period());
        int count = 0;
        for (Element row : rows) {
            if (count >= topN) {
                break;
            }
            HotItem item = parseItem(row, coordinate);
            if (item == null) {
                continue;
            }
            items.add(item);
            count++;
        }

        return FetchResult.builder()
                .coordinate(coordinate)
                .items(items)
                .build();
    }

    private HotItem parseItem(Element row, FetchCoordinate coordinate) {
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
                .description(description)
                .build();
    }

    private String getPeriod(PeriodEnum period) {
        return switch (period) {
            case DAILY -> "daily";
            case WEEKLY -> "weekly";
            case MONTHLY -> "monthly";
        };
    }

    private int getTopN(PeriodEnum period) {
        CrawlerProperties.TopNConfig topN = crawlerProperties.getGithub().getTopN();
        return switch (period) {
            case DAILY -> topN.getDaily();
            case WEEKLY -> topN.getWeekly();
            case MONTHLY -> topN.getMonthly();
        };
    }

    @Override
    public void download(HotItem item, FetchCoordinate coordinate) throws IOException {
        String repoPath = item.getUrl().replace("https://github.com/", "");
        String[] parts = repoPath.split("/");
        if (parts.length < 2) {
            throw new IOException("Invalid repo path: " + item.getUrl());
        }
        String owner = parts[0];
        String repo = parts[1];

        if (tryDownloadViaApi(owner, repo, item, coordinate)) {
            return;
        }
        throw new IOException("Failed to download README for " + item.getTitle()
                + " (API failed): " + item.getUrl());
    }

    /**
     * 主路径：GitHub Contents API，自动解析默认分支与 README 文件名。
     * 200 且 content 非空 -> base64 解码写入；302 或 content 为空但有 download_url -> 直接取该 raw 地址。
     * 其他状态码或网络异常 -> 直接结束。
     */
    private boolean tryDownloadViaApi(String owner, String repo, HotItem item, FetchCoordinate coordinate) {
        String url = String.format(crawlerProperties.getGithub().getContentBaseUrl(), owner, repo);
        try {
            HttpResponse<String> response = httpUtil.getNoFollow(url,
                    Map.of("Accept", "application/vnd.github+json"));
            int code = response.statusCode();
            if (code == 200) {
                String content = extractApiContent(response.body());
                if (content != null) {
                    saveContent(content, item, coordinate);
                    return true;
                }
                String downloadUrl = extractApiDownloadUrl(response.body());
                if (downloadUrl != null) {
                    return fetchRawAndSave(downloadUrl, item, coordinate);
                }
                return false;
            }
            if (code == 301 || code == 302) {
                String location = response.headers().firstValue("Location").orElse(null);
                if (location != null) {
                    return fetchRawAndSave(location, item, coordinate);
                }
                return false;
            }
            return false;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 对给定 raw 地址做一次 GET 并尝试保存（用于 API 重定向 / download_url 场景，不再重试）
     */
    private boolean fetchRawAndSave(String url, HotItem item, FetchCoordinate coordinate) throws IOException {
        HttpResponse<String> response = httpUtil.getFollow(url, null);
        if (response.statusCode() == 200) {
            saveContent(response.body(), item, coordinate);
            return true;
        }
        return false;
    }

    private void saveContent(String content, HotItem item, FetchCoordinate coordinate) throws IOException {
        String repoPath = item.getUrl().replace("https://github.com/", "").replace("/", "_");
        Path contentFilePath = FilePathUtils.getContentFilePath(coordinate.source(), coordinate.period(),
                coordinate.date(), coordinate.category(), coordinate.language(), repoPath);
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

}
