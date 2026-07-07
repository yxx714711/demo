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
public class JuejinCrawler extends AbstractCrawler {

    private static final String HOT_URL = "https://juejin.cn/hot/articles/%s";

    private final CrawlerProperties crawlerProperties;
    private final PageFetcher pageFetcher;

    @Override
    protected DataSourceEnum getDataSource() {
        return DataSourceEnum.JUEJIN;
    }

    @Override
    protected List<PeriodEnum> getSupportedPeriods() {
        return List.of(PeriodEnum.DAILY);
    }

    @Override
    protected List<String> getCategories() {
        List<String> categories = crawlerProperties.getJuejin().getCategories();
        return CollectionUtils.isEmpty(categories) ? List.of("all") : categories;
    }

    @Override
    protected List<String> getLanguages() {
        return new ArrayList<>();
    }

    @Override
    public FetchResult crawl(CrawlerContext context) {
        String categoryParam = "all".equals(context.getCategory()) ? "" : context.getCategory();
        String url = String.format(HOT_URL, categoryParam);
        log.info("Crawling Juejin hot articles: {}", url);

        Document document = pageFetcher.fetchDocument(url, doc -> !doc.select("a.article-item-link").isEmpty());
        Elements items = document.select("a.article-item-link");
        if (items.isEmpty()) {
            log.warn("No Juejin items found for url: {}", url);
        }

        List<HotItem> hotItems = new ArrayList<>();
        int topN = crawlerProperties.getJuejin().getDailyTopN();
        int count = 0;
        for (Element itemElement : items) {
            if (count >= topN) {
                break;
            }
            HotItem item = parseItem(itemElement, context);
            if (item == null) {
                continue;
            }
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
        Document document = pageFetcher.fetchDocument(item.getUrl());
        Element articleElement = document.selectFirst("article.markdown-body");
        if (articleElement == null) {
            articleElement = document.selectFirst(".article-content");
        }
        if (articleElement == null) {
            log.warn("Article content not found for url: {}", item.getUrl());
            item.setContentPath("");
            return;
        }
        String content = articleElement.text();
        String slug = extractSlug(item.getUrl());
        Path contentFilePath = FilePathUtils.getContentFilePath(context.getSource(), context.getPeriod(),
                context.getDate(), context.getCategory(), context.getLanguage(), slug);
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
