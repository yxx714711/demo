package com.waic.springaidemo.crawler.service.impl;

import com.waic.springaidemo.crawler.config.CrawlerProperties;
import com.waic.springaidemo.common.entity.FetchResult;
import com.waic.springaidemo.common.entity.HotItem;
import com.waic.springaidemo.common.enums.DataSourceEnum;
import com.waic.springaidemo.common.enums.PeriodEnum;
import com.waic.springaidemo.common.utils.FilePathUtils;
import com.waic.springaidemo.crawler.service.CrawlerContext;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Gitee 抓取器
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GiteeCrawler extends AbstractCrawler {

    private static final String EXPLORE_URL = "https://gitee.com/explore/%s?lang=%s&type=hot";
    private static final List<String> SUPPORTED_README_BRANCHES = Arrays.asList("master", "main");

    private final CrawlerProperties crawlerProperties;
    private final PageFetcher pageFetcher;
    private final HttpClient httpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();

    @Override
    protected DataSourceEnum getDataSource() {
        return DataSourceEnum.GITEE;
    }

    @Override
    protected List<PeriodEnum> getSupportedPeriods() {
        return Arrays.asList(PeriodEnum.DAILY);
    }

    @Override
    protected List<String> getCategories() {
        List<String> categories = crawlerProperties.getGitee().getCategories();
        return CollectionUtils.isEmpty(categories) ? Arrays.asList("人工智能", "程序开发") : categories;
    }

    @Override
    protected List<String> getLanguages() {
        List<String> languages = crawlerProperties.getGitee().getLanguages();
        return CollectionUtils.isEmpty(languages) ? Arrays.asList("java") : languages;
    }

    @Override
    public FetchResult crawl(CrawlerContext context) {
        String url = String.format(EXPLORE_URL, context.getCategory(), context.getLanguage());
        log.info("Crawling Gitee explore: {}", url);

        Document document = pageFetcher.fetchDocument(url);
        Elements items = document.select(".repository-item");
        if (items.isEmpty()) {
            log.warn("No Gitee items found for url: {}", url);
        }

        List<HotItem> hotItems = new ArrayList<>();
        int topN = crawlerProperties.getGitee().getDailyTopN();
        int count = 0;
        for (Element itemElement : items) {
            if (count >= topN) {
                break;
            }
            HotItem item = parseItem(itemElement, context);
            if (item == null) {
                continue;
            }
            downloadReadme(item, context);
            hotItems.add(item);
            count++;
        }

        return FetchResult.builder()
                .source(context.getSource())
                .period(context.getPeriod())
                .date(context.getDate())
                .category(context.getCategory())
                .language(context.getLanguage())
                .items(hotItems)
                .build();
    }

    private HotItem parseItem(Element itemElement, CrawlerContext context) {
        Element linkElement = itemElement.selectFirst("a.title");
        if (linkElement == null) {
            return null;
        }
        String relativeUrl = linkElement.attr("href");
        if (relativeUrl.isBlank()) {
            return null;
        }
        String fullUrl = "https://gitee.com" + relativeUrl;
        String title = linkElement.text().trim();
        String description = "";
        Element descElement = itemElement.selectFirst(".desc");
        if (descElement != null) {
            description = descElement.text().trim();
        }

        String repoPath = relativeUrl.startsWith("/") ? relativeUrl.substring(1) : relativeUrl;
        return HotItem.builder()
                .id("gitee_" + repoPath.replace("/", "_"))
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

    private void downloadReadme(HotItem item, CrawlerContext context) {
        String repoPath = item.getUrl().replace("https://gitee.com/", "");
        String[] parts = repoPath.split("/");
        if (parts.length < 2) {
            return;
        }
        String owner = parts[0];
        String repo = parts[1];

        for (String branch : SUPPORTED_README_BRANCHES) {
            String rawUrl = String.format("https://gitee.com/%s/%s/raw/%s/README.md", owner, repo, branch);
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(rawUrl))
                        .header("User-Agent", "Mozilla/5.0")
                        .GET()
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    String content = response.body();
                    String slug = owner + "_" + repo;
                    Path contentPath = FilePathUtils.getContentFilePath(context.getSource(), context.getPeriod(),
                            context.getDate(), context.getCategory(), context.getLanguage(), slug);
                    Files.createDirectories(contentPath.getParent());
                    Files.writeString(contentPath, content);
                    item.setContentPath(contentPath.toString().replace("\\", "/"));
                    return;
                }
            } catch (IOException | InterruptedException e) {
                log.warn("Failed to download Gitee README from {}: {}", rawUrl, e.getMessage());
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}
