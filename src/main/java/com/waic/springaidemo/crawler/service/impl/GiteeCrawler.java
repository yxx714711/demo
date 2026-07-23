package com.waic.springaidemo.crawler.service.impl;

import com.waic.springaidemo.crawler.config.CrawlerProperties;
import com.waic.springaidemo.common.entity.CrawlCoordinate;
import com.waic.springaidemo.common.entity.CrawlResult;
import com.waic.springaidemo.common.entity.HotItem;
import com.waic.springaidemo.common.enums.DataSourceEnum;
import com.waic.springaidemo.common.enums.PeriodEnum;
import com.waic.springaidemo.common.exception.ContentNotFoundException;
import com.waic.springaidemo.crawler.service.Crawler;
import com.waic.springaidemo.crawler.utils.HttpUtil;
import com.waic.springaidemo.crawler.utils.PageCrawlUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Gitee 抓取器
 * @author 10542
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GiteeCrawler implements Crawler {

    private static final List<String> SUPPORTED_README_BRANCHES = Arrays.asList("master", "main");

    private static final String DAILY_TAB_SELECTOR = "[data-tab='daily-trending'] .explore-trending-projects__list-item";
    private static final String WEEKLY_TAB_SELECTOR = "[data-tab='weekly-trending'] .explore-trending-projects__list-item";

    private final CrawlerProperties crawlerProperties;
    private final PageCrawlUtil pageCrawlUtil;
    private final HttpUtil httpUtil;

    @Override
    public DataSourceEnum getDataSource() {
        return DataSourceEnum.GITEE;
    }

    @Override
    public List<PeriodEnum> getPeriods() {
        return List.of(PeriodEnum.DAILY, PeriodEnum.WEEKLY);
    }

    @Override
    public List<String> getCategories() {
        // 缺失分类维度返回空列表（而非 "all"），使抓取坐标的 category 为真正的 null，
        // 下游 data 驱动汇总可直接用 == null 判断维度是否存在。
        return crawlerProperties.getGitee().getCategories();
    }

    @Override
    public List<String> getLanguages() {
        // 缺失语言维度返回空列表（而非 "all"），使抓取坐标的 language 为真正的 null。
        return crawlerProperties.getGitee().getLanguages();
    }

    @Override
    public CrawlResult crawl(CrawlCoordinate coordinate) {
        PeriodEnum period = coordinate.period();
        String selector = getTabSelector(period);
        // 维度为 null 时回退为 "all"（Gitee 探索页 URL 的合法取值），但抓取坐标仍存 null，保持数据干净。
        String categoryParam = coordinate.category() == null ? "all" : coordinate.category();
        String langParam = coordinate.language() == null ? "all" : coordinate.language();
        String url = String.format(crawlerProperties.getGitee().getHotBaseUrl(), categoryParam, langParam);
        log.info("Crawling Gitee explore: {} period={}", url, period);

        Document document = pageCrawlUtil.crawlDocument(url);

        List<HotItem> hotItems = parseItems(document, selector, coordinate);

        return CrawlResult.builder()
                .coordinate(coordinate)
                .items(hotItems)
                .build();
    }

    /**
     * 解析指定 tab 下的热门项目列表，结果追加到 hotItems
     */
    private List<HotItem> parseItems(Document document, String selector, CrawlCoordinate coordinate) {
        Elements rows = document.select(selector);
        if (rows.isEmpty()) {
            log.warn("No Gitee trending items found for url period: {}", coordinate.period());
            return Collections.emptyList();
        }

        List<HotItem> hotItems = new ArrayList<>();
        int topN = getTopN(coordinate.period());
        int count = 0;
        for (Element row : rows) {
            if (count >= topN) {
                break;
            }
            HotItem item = parseItem(row);
            if (item == null) {
                continue;
            }
            hotItems.add(item);
            count++;
        }
        return hotItems;
    }

    private HotItem parseItem(Element itemElement) {
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
                .description(description)
                .contentPath(HotItem.CONTENT_PENDING)
                .build();
    }

    /**
     * Gitee 页面 daily/weekly 同页共存，按周期取对应 tab 的解析选择器。
     */
    private static String getTabSelector(PeriodEnum period) {
        return period == PeriodEnum.DAILY ? DAILY_TAB_SELECTOR : WEEKLY_TAB_SELECTOR;
    }

    /**
     * 按周期解析 Gitee 的 TopN，DAILY/WEEKLY 分别对应 top-n 配置。
     */
    private int getTopN(PeriodEnum period) {
        CrawlerProperties.TopNConfig topN = crawlerProperties.getGitee().getTopN();
        return period == PeriodEnum.DAILY ? topN.getDaily() : topN.getWeekly();
    }

    @Override
    public String crawlContent(HotItem item) throws IOException, ContentNotFoundException {
        String repoPath = item.getUrl().replace("https://gitee.com/", "");
        String[] parts = repoPath.split("/");
        if (parts.length < 2) {
            throw new ContentNotFoundException("Invalid repo path: " + item.getUrl());
        }
        String owner = parts[0];
        String repo = parts[1];

        ContentNotFoundException notFound = null;
        for (String branch : SUPPORTED_README_BRANCHES) {
            String rawUrl = String.format(crawlerProperties.getGitee().getContentBaseUrl(), owner, repo, branch);
            HttpResponse<String> response = httpUtil.getFollow(rawUrl, null);
            int status = response.statusCode();
            if (status == 200) {
                return response.body();
            }
            if (status == 404) {
                notFound = new ContentNotFoundException(
                        "README not found (404) for " + item.getTitle() + " branch=" + branch);
                continue;
            }
            throw new IOException("Failed to fetch README for " + item.getTitle()
                    + " branch=" + branch + " status=" + status);
        }
        throw notFound != null ? notFound
                : new ContentNotFoundException("Failed to download README for " + item.getTitle());
    }
}
