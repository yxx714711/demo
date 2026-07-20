package com.waic.springaidemo.crawler.service.impl;

import com.waic.springaidemo.crawler.config.CrawlerProperties;
import com.waic.springaidemo.common.entity.FetchCoordinate;
import com.waic.springaidemo.common.entity.FetchResult;
import com.waic.springaidemo.common.entity.HotItem;
import com.waic.springaidemo.common.enums.DataSourceEnum;
import com.waic.springaidemo.common.enums.PeriodEnum;
import com.waic.springaidemo.common.utils.FilePathUtils;
import com.waic.springaidemo.crawler.service.Crawler;
import com.waic.springaidemo.crawler.utils.Html2MarkdownUtil;
import com.waic.springaidemo.crawler.utils.PageFetcherUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 掘金抓取器
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JuejinCrawler implements Crawler {

    private static final String HOT_URL = "https://juejin.cn/hot/articles/%s";
    private static final String HOT_URL_ALL = "https://juejin.cn/hot/articles";

    private final CrawlerProperties crawlerProperties;
    private final PageFetcherUtil pageFetcherUtil;

    @Override
    public DataSourceEnum getDataSource() {
        return DataSourceEnum.JUEJIN;
    }

    @Override
    public List<PeriodEnum> getPeriods() {
        return List.of(PeriodEnum.DAILY);
    }

    @Override
    public List<String> getCategories() {
        List<String> categories = crawlerProperties.getJuejin().getCategories();
        return CollectionUtils.isEmpty(categories) ? List.of("all") : categories;
    }

    @Override
    public List<String> getLanguages() {
        return new ArrayList<>();
    }

    @Override
    public FetchResult crawl(FetchCoordinate coordinate) {
        String url = "all".equals(coordinate.category())
                ? HOT_URL_ALL
                : String.format(HOT_URL, coordinate.category());
        log.info("Crawling Juejin hot articles: {}", url);

        // 掘金为 Nuxt SSR/SPA，Jsoup 直连会被反爬返回空壳，故直接走 Playwright，
        // 并显式等待列表选择器出现后再取内容（避免 waitForLoadState 过早返回空壳）。
        Document document = pageFetcherUtil.fetchDocument(url, "a.article-item-link", null);
        Elements items = document.select("a.article-item-link");
        if (items.isEmpty()) {
            log.warn("No Juejin items found for url: {}", url);
        }

        List<HotItem> hotItems = new ArrayList<>();
        int topN = crawlerProperties.getJuejin().getTopN().getDaily();
        int count = 0;
        for (Element itemElement : items) {
            if (count >= topN) {
                break;
            }
            HotItem item = parseItem(itemElement, coordinate);
            if (item == null) {
                continue;
            }
            hotItems.add(item);
            count++;
        }

        return FetchResult.builder()
                .coordinate(coordinate)
                .items(hotItems)
                .build();
    }

    private HotItem parseItem(Element itemElement, FetchCoordinate coordinate) {
        // itemElement 本身就是 a.article-item-link，href 即文章链接
        String relativeUrl = itemElement.attr("href");
        if (relativeUrl.isBlank()) {
            return null;
        }
        String fullUrl = relativeUrl.startsWith("http") ? relativeUrl : "https://juejin.cn" + relativeUrl;
        Element titleElement = itemElement.selectFirst(".article-title");
        if (titleElement == null) {
            return null;
        }
        String title = titleElement.text().trim();
        String description = "";
        Element descElement = itemElement.selectFirst(".article-author-name-text");
        if (descElement != null) {
            description = "作者: " + descElement.text().trim();
        }

        String slug = extractSlug(fullUrl);
        return HotItem.builder()
                .id("juejin_" + slug)
                .title(title)
                .url(fullUrl)
                .summary(description)
                .fetchedAt(LocalDateTime.now())
                .build();
    }

    @Override
    public void download(HotItem item, FetchResult result) throws IOException {
        // 详情页同样走 Playwright，并等待正文容器 #article-root 渲染完成。
        Document document = pageFetcherUtil.fetchDocument(item.getUrl(), "#article-root", null);
        Element articleElement = document.selectFirst("#article-root");
        if (articleElement == null) {
            log.warn("Article content not found for url: {}", item.getUrl());
            item.setContentPath("");
            return;
        }
        String content = Html2MarkdownUtil.convert(articleElement);
        if (content.isBlank()) {
            log.warn("Article content is blank for url: {}", item.getUrl());
            item.setContentPath("");
            return;
        }
        FetchCoordinate coordinate = result.getCoordinate();
        String slug = extractSlug(item.getUrl());
        Path contentFilePath = FilePathUtils.getContentFilePath(coordinate.source(), coordinate.period(),
                coordinate.date(), coordinate.category(), coordinate.language(), slug);
        Files.createDirectories(contentFilePath.getParent());
        Files.writeString(contentFilePath, content);
        item.setContentPath(contentFilePath.toString().replace("\\", "/"));
        log.info("Downloaded article for {} to {}", item.getTitle(), item.getContentPath());
    }

    private String extractSlug(String url) {
        String[] parts = url.split("/");
        if (parts.length == 0) {
            return "unknown";
        }
        String last = parts[parts.length - 1];
        return last.replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fa5_-]", "_");
    }
}
