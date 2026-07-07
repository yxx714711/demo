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
 * GitHub 抓取器
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GithubCrawler extends AbstractCrawler {

    private static final String TRENDING_URL = "https://github.com/trending/%s?since=%s";
    private static final List<String> SUPPORTED_README_BRANCHES = Arrays.asList("main", "master");

    private final CrawlerProperties crawlerProperties;
    private final PageFetcher pageFetcher;
    private final HttpClient httpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();

    @Override
    protected DataSourceEnum getDataSource() {
        return DataSourceEnum.GITHUB;
    }

    @Override
    protected List<PeriodEnum> getSupportedPeriods() {
        return Arrays.asList(PeriodEnum.DAILY, PeriodEnum.WEEKLY, PeriodEnum.MONTHLY);
    }

    @Override
    protected List<String> getCategories() {
        return new ArrayList<>();
    }

    @Override
    protected List<String> getLanguages() {
        List<String> languages = crawlerProperties.getGithub().getLanguages();
        return CollectionUtils.isEmpty(languages) ? List.of("all") : languages;
    }

    @Override
    public FetchResult crawl(CrawlerContext context) {
        String periodParam = mapPeriod(context.getPeriod());
        String langParam = "all".equals(context.getLanguage()) ? "" : context.getLanguage();
        String url = String.format(TRENDING_URL, langParam, periodParam);
        log.info("Crawling GitHub trending: {}", url);

        Document document = pageFetcher.fetchDocument(url);
        Elements rows = document.select("article.Box-row");
        if (rows.isEmpty()) {
            log.warn("No trending items found for url: {}", url);
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
            downloadReadme(item, context);
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

    private HotItem parseRow(Element row, CrawlerContext context) {
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

    private void downloadReadme(HotItem item, CrawlerContext context) {
        String repoPath = item.getUrl().replace("https://github.com/", "");
        String[] parts = repoPath.split("/");
        if (parts.length < 2) {
            return;
        }
        String owner = parts[0];
        String repo = parts[1];

        for (String branch : SUPPORTED_README_BRANCHES) {
            String rawUrl = String.format("https://raw.githubusercontent.com/%s/%s/%s/README.md", owner, repo, branch);
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
                log.warn("Failed to download README from {}: {}", rawUrl, e.getMessage());
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
            }
        }
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
