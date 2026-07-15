package com.waic.springaidemo.crawler.service.impl;

import com.waic.springaidemo.crawler.config.CrawlerProperties;
import com.waic.springaidemo.common.entity.FetchResult;
import com.waic.springaidemo.common.entity.HotItem;
import com.waic.springaidemo.common.enums.DataSourceEnum;
import com.waic.springaidemo.common.enums.PeriodEnum;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Gitee 抓取器
 * @author 10542
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GiteeCrawler extends AbstractCrawler {

    private static final String EXPLORE_URL = "https://gitee.com/explore/%s?lang=%s&type=hot";
    private static final List<String> SUPPORTED_README_BRANCHES = Arrays.asList("master", "main");

    private static final String DAILY_TAB_SELECTOR = "[data-tab='daily-trending'] .explore-trending-projects__list-item";
    private static final String WEEKLY_TAB_SELECTOR = "[data-tab='weekly-trending'] .explore-trending-projects__list-item";

    private final CrawlerProperties crawlerProperties;
    private final PageFetcher pageFetcher;
    private final HttpClient httpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();

    @Override
    protected DataSourceEnum getDataSource() {
        return DataSourceEnum.GITEE;
    }

    @Override
    protected List<PeriodEnum> getSupportedPeriods() {
        return List.of(PeriodEnum.DAILY);
    }

    @Override
    protected List<String> getCategories() {
        List<String> categories = crawlerProperties.getGitee().getCategories();
        return CollectionUtils.isEmpty(categories) ? List.of("all") : categories;
    }

    @Override
    protected List<String> getLanguages() {
        List<String> languages = crawlerProperties.getGitee().getLanguages();
        return CollectionUtils.isEmpty(languages) ? List.of("all") : languages;
    }

    @Override
    public FetchResult crawl(CrawlerContext context) {
        String url = String.format(EXPLORE_URL, context.getCategory(), context.getLanguage());
        log.info("Crawling Gitee explore: {}", url);

        Document document = pageFetcher.fetchDocument(url);
        int topN = crawlerProperties.getGitee().getDailyTopN();

        List<HotItem> hotItems = new ArrayList<>();
        parseTabItems(document, DAILY_TAB_SELECTOR, PeriodEnum.DAILY, topN, context, hotItems);
        parseTabItems(document, WEEKLY_TAB_SELECTOR, PeriodEnum.WEEKLY, topN, context, hotItems);

        return FetchResult.builder()
                .source(context.getSource())
                .period(context.getPeriod())
                .date(context.getDate())
                .category(context.getCategory())
                .language(context.getLanguage())
                .items(hotItems)
                .build();
    }

    /**
     * 解析指定 tab 下的热门项目列表，结果追加到 hotItems
     */
    private void parseTabItems(Document document, String selector, PeriodEnum period, int topN,
                               CrawlerContext context, List<HotItem> hotItems) {
        Elements items = document.select(selector);
        if (items.isEmpty()) {
            log.warn("No Gitee trending items found for url period: {}", period);
            return;
        }
        int count = 0;
        for (Element itemElement : items) {
            if (count >= topN) {
                break;
            }
            HotItem item = parseItem(itemElement, context, period);
            if (item == null) {
                continue;
            }
            hotItems.add(item);
            count++;
        }
    }

    private HotItem parseItem(Element itemElement, CrawlerContext context, PeriodEnum period) {
        Element linkElement = itemElement.selectFirst(".title a");
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
        Element descElement = itemElement.selectFirst(".description");
        if (descElement != null) {
            description = descElement.text().trim();
        }

        String repoPath = relativeUrl.startsWith("/") ? relativeUrl.substring(1) : relativeUrl;
        return HotItem.builder()
                .id("gitee_" + repoPath.replace("/", "_"))
                .title(title)
                .url(fullUrl)
                .source(context.getSource())
                .period(period)
                .category(context.getCategory())
                .language(context.getLanguage())
                .summary(description)
                .fetchedAt(LocalDateTime.now())
                .build();
    }

    @Override
    public void download(HotItem item, CrawlerContext context) throws IOException {
        String repoPath = item.getUrl().replace("https://gitee.com/", "");
        String[] parts = repoPath.split("/");
        if (parts.length < 2) {
            throw new IOException("Invalid repo path: " + item.getUrl());
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
                    Path contentFilePath = FilePathUtils.getContentFilePath(context.getSource(), context.getPeriod(),
                            context.getDate(), context.getCategory(), context.getLanguage(), slug);
                    Files.createDirectories(contentFilePath.getParent());
                    Files.writeString(contentFilePath, content);
                    item.setContentPath(contentFilePath.toString().replace("\\", "/"));
                    log.info("Downloaded README for {} to {}", item.getTitle(), item.getContentPath());
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while downloading Gitee README from " + rawUrl, e);
            }
        }
        throw new IOException("Failed to download README for " + item.getTitle()
                + " (all branches failed): " + item.getUrl());
    }
}
