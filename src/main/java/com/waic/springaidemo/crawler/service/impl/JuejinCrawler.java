package com.waic.springaidemo.crawler.service.impl;

import com.waic.springaidemo.crawler.config.CrawlerProperties;
import com.waic.springaidemo.common.entity.CrawlCoordinate;
import com.waic.springaidemo.common.entity.CrawlResult;
import com.waic.springaidemo.common.entity.HotItem;
import com.waic.springaidemo.common.enums.DataSourceEnum;
import com.waic.springaidemo.common.enums.PeriodEnum;
import com.waic.springaidemo.common.exception.ContentNotFoundException;
import com.waic.springaidemo.crawler.service.Crawler;
import com.waic.springaidemo.crawler.utils.Html2MarkdownUtil;
import com.waic.springaidemo.crawler.utils.PageCrawlUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 掘金抓取器
 * @author 10542
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JuejinCrawler implements Crawler {

    private final CrawlerProperties crawlerProperties;
    private final PageCrawlUtil pageCrawlUtil;

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
        // 缺失分类维度返回空列表（而非 "all"），使抓取坐标的 category 为真正的 null，
        // 下游 data 驱动汇总可直接用 == null 判断维度是否存在。
        return crawlerProperties.getJuejin().getCategories();
    }

    @Override
    public List<String> getLanguages() {
        return new ArrayList<>();
    }

    @Override
    public CrawlResult crawl(CrawlCoordinate coordinate) {
        String url = coordinate.category() == null || coordinate.category().equals("all")
                ? crawlerProperties.getJuejin().getHotBaseUrl()
                : crawlerProperties.getJuejin().getHotBaseUrl() + "/" + coordinate.category();
        log.info("Crawling Juejin hot articles: {}", url);

        // 掘金为 Nuxt SSR/SPA，Jsoup 直连会被反爬返回空壳，故直接走 Playwright，
        // 并显式等待列表选择器出现后再取内容（避免 waitForLoadState 过早返回空壳）。
        Document document = pageCrawlUtil.crawlDocument(url, "a.article-item-link", null);
        Elements rows = document.select("a.article-item-link");
        if (rows.isEmpty()) {
            log.warn("No Juejin items found for url: {}", url);
        }

        List<HotItem> items = new ArrayList<>();
        int topN = crawlerProperties.getJuejin().getTopN().getDaily();
        int count = 0;
        for (Element row : rows) {
            if (count >= topN) {
                break;
            }
            HotItem item = parseItem(row);
            if (item == null) {
                continue;
            }
            items.add(item);
            count++;
        }

        return CrawlResult.builder()
                .coordinate(coordinate)
                .items(items)
                .build();
    }

    private HotItem parseItem(Element row) {
        // row 本身就是 a.article-item-link，href 即文章链接
        String relativeUrl = row.attr("href");
        if (relativeUrl.isBlank()) {
            return null;
        }
        String fullUrl = relativeUrl.startsWith("http") ? relativeUrl : "https://juejin.cn" + relativeUrl;
        Element titleElement = row.selectFirst(".article-title");
        if (titleElement == null) {
            return null;
        }
        String title = titleElement.text().trim();
        String description = "";
        Element descElement = row.selectFirst(".article-author-name-text");
        if (descElement != null) {
            description = "作者: " + descElement.text().trim();
        }

        String slug = extractSlug(fullUrl);
        return HotItem.builder()
                .id("juejin_" + slug)
                .title(title)
                .url(fullUrl)
                .description(description)
                .contentPath(HotItem.CONTENT_PENDING)
                .build();
    }

    @Override
    public String crawlContent(HotItem item) throws ContentNotFoundException {
        // 详情页同样走 Playwright，并等待正文容器 #article-root 渲染完成。
        Document document = pageCrawlUtil.crawlDocument(item.getUrl(), "#article-root", null);
        Element articleElement = document.selectFirst("#article-root");
        if (articleElement == null) {
            throw new ContentNotFoundException("Article content not found for url: " + item.getUrl());
        }
        String content = Html2MarkdownUtil.convert(articleElement);
        if (content.isBlank()) {
            throw new ContentNotFoundException("Article content is blank for url: " + item.getUrl());
        }
        return content;
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
