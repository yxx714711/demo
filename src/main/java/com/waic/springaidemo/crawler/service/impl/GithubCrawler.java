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
import org.springframework.util.CollectionUtils;

import java.io.IOException;
import java.net.http.HttpResponse;
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

    private static final List<String> SUPPORTED_README_BRANCHES = List.of("main", "master");

    private final CrawlerProperties crawlerProperties;
    private final PageCrawlUtil pageCrawlUtil;
    private final HttpUtil httpUtil;

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
    public CrawlResult crawl(CrawlCoordinate coordinate) {
        String periodParam = getPeriod(coordinate.period());
        String langParam = "all".equals(coordinate.language()) ? "" : coordinate.language();
        String url = String.format(crawlerProperties.getGithub().getHotBaseUrl(), langParam, periodParam);
        log.info("正在抓取 GitHub 热门仓库: {}", url);

        Document document = pageCrawlUtil.crawlDocument(url, "article.Box-row",
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
                .contentPath(HotItem.CONTENT_PENDING)
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
    public String crawlContent(HotItem item) throws IOException, ContentNotFoundException {
        String repoPath = item.getUrl().replace("https://github.com/", "");
        String[] parts = repoPath.split("/");
        if (parts.length < 2) {
            throw new ContentNotFoundException("Invalid repo path: " + item.getUrl());
        }
        String owner = parts[0];
        String repo = parts[1];

        ContentNotFoundException notFound = null;
        for (String branch : SUPPORTED_README_BRANCHES) {
            String rawUrl = String.format(crawlerProperties.getGithub().getContentBaseUrl(), owner, repo, branch);
            HttpResponse<String> response = httpUtil.getFollow(rawUrl, null);
            int status = response.statusCode();
            if (status == 200) {
                return response.body();
            }
            if (status == 404) {
                // 该分支无 README，尝试下一分支（跨分支容忍）
                notFound = new ContentNotFoundException(
                        "README not found (404) for " + item.getTitle() + " branch=" + branch);
                continue;
            }
            // 其余非 200（瞬时/服务端错误）→ 直接抛 IOException 保持 PENDING 重试
            throw new IOException("Failed to fetch README for " + item.getTitle()
                    + " branch=" + branch + " status=" + status);
        }
        throw notFound != null ? notFound
                : new ContentNotFoundException("Failed to download README for " + item.getTitle());
    }
}
