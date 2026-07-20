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

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
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

    private static final String EXPLORE_URL = "https://gitee.com/explore/%s?lang=%s&type=hot";
    private static final List<String> SUPPORTED_README_BRANCHES = Arrays.asList("master", "main");

    private static final String DAILY_TAB_SELECTOR = "[data-tab='daily-trending'] .explore-trending-projects__list-item";
    private static final String WEEKLY_TAB_SELECTOR = "[data-tab='weekly-trending'] .explore-trending-projects__list-item";

    private final CrawlerProperties crawlerProperties;
    private final PageFetcherUtil pageFetcherUtil;
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
        List<String> categories = crawlerProperties.getGitee().getCategories();
        return CollectionUtils.isEmpty(categories) ? List.of("all") : categories;
    }

    @Override
    public List<String> getLanguages() {
        List<String> languages = crawlerProperties.getGitee().getLanguages();
        return CollectionUtils.isEmpty(languages) ? List.of("all") : languages;
    }

    @Override
    public FetchResult crawl(FetchCoordinate coordinate) {
        PeriodEnum period = coordinate.period();
        String selector = mapTabSelector(period);
        String url = String.format(EXPLORE_URL, coordinate.category(), coordinate.language());
        log.info("Crawling Gitee explore: {} period={}", url, period);

        Document document = pageFetcherUtil.fetchDocument(url);

        List<HotItem> hotItems = parseTabItems(document, selector, coordinate);

        return FetchResult.builder()
                .coordinate(coordinate)
                .items(hotItems)
                .build();
    }

    /**
     * 解析指定 tab 下的热门项目列表，结果追加到 hotItems
     */
    private List<HotItem> parseTabItems(Document document, String selector, FetchCoordinate coordinate) {
        Elements rows = document.select(selector);
        if (rows.isEmpty()) {
            log.warn("No Gitee trending items found for url period: {}", coordinate.period());
            return Collections.emptyList();
        }

        List<HotItem> hotItems = new ArrayList<>();
        int topN = resolveTopN(coordinate.period());
        int count = 0;
        for (Element row : rows) {
            if (count >= topN) {
                break;
            }
            HotItem item = parseRow(row, coordinate);
            if (item == null) {
                continue;
            }
            hotItems.add(item);
            count++;
        }
        return hotItems;
    }

    private HotItem parseRow(Element itemElement, FetchCoordinate coordinate) {
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
                .summary(description)
                .fetchedAt(LocalDateTime.now())
                .build();
    }

    @Override
    public void download(HotItem item, FetchResult result) throws IOException {
        String repoPath = item.getUrl().replace("https://gitee.com/", "");
        String[] parts = repoPath.split("/");
        if (parts.length < 2) {
            throw new IOException("Invalid repo path: " + item.getUrl());
        }
        String owner = parts[0];
        String repo = parts[1];

        FetchCoordinate coordinate = result.getCoordinate();
        for (String branch : SUPPORTED_README_BRANCHES) {
            String rawUrl = String.format("https://gitee.com/%s/%s/raw/%s/README.md", owner, repo, branch);
            HttpResponse<String> response = httpUtil.getFollow(rawUrl, null);
            if (response.statusCode() == 200) {
                String content = response.body();
                String slug = owner + "_" + repo;
                Path contentFilePath = FilePathUtils.getContentFilePath(coordinate.source(), coordinate.period(),
                        coordinate.date(), coordinate.category(), coordinate.language(), slug);
                Files.createDirectories(contentFilePath.getParent());
                Files.writeString(contentFilePath, content);
                item.setContentPath(contentFilePath.toString().replace("\\", "/"));
                log.info("Downloaded README for {} to {}", item.getTitle(), item.getContentPath());
                return;
            }
        }
        throw new IOException("Failed to download README for " + item.getTitle()
                + " (all branches failed): " + item.getUrl());
    }

    /**
     * Gitee 页面 daily/weekly 同页共存，按周期取对应 tab 的解析选择器。
     */
    private static String mapTabSelector(PeriodEnum period) {
        return switch (period) {
            case DAILY -> DAILY_TAB_SELECTOR;
            case WEEKLY -> WEEKLY_TAB_SELECTOR;
            default -> DAILY_TAB_SELECTOR;
        };
    }

    /**
     * 按周期解析 Gitee 的 TopN，DAILY/WEEKLY 分别对应 top-n 配置。
     */
    private int resolveTopN(PeriodEnum period) {
        CrawlerProperties.TopNConfig topN = crawlerProperties.getGitee().getTopN();
        return switch (period) {
            case DAILY -> topN.getDaily();
            case WEEKLY -> topN.getWeekly();
            default -> topN.getDaily();
        };
    }
}
