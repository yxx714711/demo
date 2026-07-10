package com.waic.springaidemo.pipeline.service.impl;

import com.waic.springaidemo.ai.service.ReportGenerator;
import com.waic.springaidemo.ai.entity.SummaryContext;
import com.waic.springaidemo.common.entity.FetchResult;
import com.waic.springaidemo.common.entity.HotItem;
import com.waic.springaidemo.common.entity.NodeSummary;
import com.waic.springaidemo.common.entity.ReportResult;
import com.waic.springaidemo.common.entity.SummaryKey;
import com.waic.springaidemo.common.enums.DataSourceEnum;
import com.waic.springaidemo.common.enums.LevelEnum;
import com.waic.springaidemo.common.enums.PeriodEnum;
import com.waic.springaidemo.crawler.entity.CrawlerContext;
import com.waic.springaidemo.crawler.service.Crawler;
import com.waic.springaidemo.crawler.service.CrawlerRegistry;
import com.waic.springaidemo.persistence.service.FetchResultRepository;
import com.waic.springaidemo.persistence.service.SummaryRepository;
import com.waic.springaidemo.pipeline.service.PipelineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pipeline 编排服务实现
 * <p>单一职责：递归后序遍历 data 树，逐节点调 {@code reportGenerator} 生成文本、
 * 组装 {@link NodeSummary} 并交持久化服务落盘。本类不调用 LLM 以外逻辑，也不直接写文件。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PipelineServiceImpl implements PipelineService {

    private static final double ITEM_SCALE = 0.15;      // D3：逐篇输出 = 本篇字数 × 15%
    private static final int ITEM_MIN_CHARS = 300;        // D3：逐篇下限
    private static final int ITEM_MAX_CHARS = 2000;       // D3：逐篇上限
    private static final int MID_MAX_CHARS = 1000;        // D3：中间聚合层（LANGUAGE/CATEGORY/SOURCE）上限
    private static final int REPORT_MAX_CHARS = 2000;    // D3：日报上限
    private static final String PLACEHOLDER = "_";       // D9/D10：占位段（_ 子目录直接拷贝）

    private final CrawlerRegistry crawlerRegistry;
    private final FetchResultRepository fetchResultRepository;
    private final SummaryRepository summaryRepository;
    private final ReportGenerator reportGenerator;

    @Override
    public List<FetchResult> runCrawl(LocalDate date, PeriodEnum period) throws IOException {
        log.info("Running crawl pipeline for date={}, period={}", date, period);

        // Step 1: 抓取元数据（不下载内容）
        List<FetchResult> results = crawlerRegistry.crawlAll(date, period);
        return persistAndDownload(results);
    }

    @Override
    public List<FetchResult> runCrawlBySource(DataSourceEnum source, LocalDate date, PeriodEnum period) throws IOException {
        log.info("Running crawl pipeline for source={}, date={}, period={}", source, date, period);

        // Step 1: 抓取指定数据源的元数据（不下载内容）
        List<FetchResult> results = crawlerRegistry.crawlBySource(source, date, period);
        return persistAndDownload(results);
    }

    /**
     * 持久化抓取结果并逐条下载正文（Step 2 + Step 3）。
     * 单条下载失败时会跳过该条并继续，contentPath 保持 "PENDING" 供后续重试。
     */
    private List<FetchResult> persistAndDownload(List<FetchResult> results) throws IOException {
        for (FetchResult result : results) {
            for (HotItem item : result.getItems()) {
                item.setContentPath("PENDING");
            }
            fetchResultRepository.save(result);
        }
        log.info("Saved {} fetch results with PENDING contentPath", results.size());

        for (FetchResult result : results) {
            CrawlerContext downloadContext = buildDownloadContext(result);
            Crawler crawler = crawlerRegistry.resolve(downloadContext);
            for (HotItem item : result.getItems()) {
                try {
                    crawler.download(item, downloadContext);
                } catch (IOException e) {
                    log.warn("Download failed for item {} ({}): {}", item.getId(), item.getTitle(), e.getMessage());
                    // contentPath 保持 "PENDING"，供后续重试
                }
            }
            fetchResultRepository.updateItems(result);
        }
        return results;
    }

    private CrawlerContext buildDownloadContext(FetchResult result) {
        return CrawlerContext.builder()
                .source(result.getSource())
                .period(result.getPeriod())
                .date(result.getDate())
                .category(result.getCategory())
                .language(result.getLanguage())
                .build();
    }

    // ===== 递归聚合编排（D1/D7/D8） =====

    @Override
    public ReportResult generateReport(PeriodEnum period, LocalDate date, boolean force) throws IOException {
        List<FetchResult> results = fetchResultRepository.loadAllByDate(period, date);
        log.info("[report] generateReport start period={} date={} force={} fetchResults={}", period, date, force, results.size());
        if (results.isEmpty()) {
            log.warn("No data found for period={} date={}", period, date);
            throw new IllegalStateException("No data found for period=" + period + " date=" + date);
        }

        // 建树：source -> category -> language -> FetchResult（后序遍历）
        Map<DataSourceEnum, Map<String, Map<String, FetchResult>>> tree = new LinkedHashMap<>();
        for (FetchResult fr : results) {
            tree.computeIfAbsent(fr.getSource(), k -> new LinkedHashMap<>())
                    .computeIfAbsent(fr.getCategory(), k -> new LinkedHashMap<>())
                    .put(fr.getLanguage(), fr);
        }

        int sourceCount = tree.size();
        int categoryCount = 0;
        List<NodeSummary> sourceSummaries = new ArrayList<>();

        for (Map.Entry<DataSourceEnum, Map<String, Map<String, FetchResult>>> srcEntry : tree.entrySet()) {
            DataSourceEnum source = srcEntry.getKey();
            Map<String, Map<String, FetchResult>> cats = srcEntry.getValue();
            categoryCount += cats.size();

            List<NodeSummary> categorySummaries = new ArrayList<>();
            for (Map.Entry<String, Map<String, FetchResult>> catEntry : cats.entrySet()) {
                String category = catEntry.getKey();
                Map<String, FetchResult> langs = catEntry.getValue();

                List<NodeSummary> langSummaries = new ArrayList<>();
                for (Map.Entry<String, FetchResult> langEntry : langs.entrySet()) {
                    langSummaries.add(summarizeLanguage(
                            period, date, source, category, langEntry.getKey(), langEntry.getValue(), force));
                }
                categorySummaries.add(aggregate(
                        period, date, source, category, null, langSummaries, LevelEnum.CATEGORY, force));
            }
            sourceSummaries.add(aggregate(
                    period, date, source, null, null, categorySummaries, LevelEnum.SOURCE, force));
        }

        NodeSummary dateSummary = aggregate(
                period, date, null, null, null, sourceSummaries, LevelEnum.DATE, force);

        SummaryKey topKey = SummaryKey.builder().period(period).date(date).build();
        NodeSummary top = summaryRepository.loadSummary(topKey);
        if (top == null) {
            top = dateSummary; // 兜底：原子写异常时回退内存对象
        }
        log.info("[report] generateReport done period={} date={} sourceCount={} categoryCount={}",
                period, date, sourceCount, categoryCount);
        return ReportResult.builder()
                .period(period)
                .date(date)
                .path(top.getPath())
                .summary(top.getSummary())
                .sourceCount(sourceCount)
                .categoryCount(categoryCount)
                .build();
    }

    /**
     * 叶子（language 层）节点：逐篇（ITEM）喂 AI 生成摘要，再聚合为 LANGUAGE 节点。
     */
    private NodeSummary summarizeLanguage(PeriodEnum period, LocalDate date, DataSourceEnum source,
                                          String category, String language, FetchResult fr, boolean force) throws IOException {
        SummaryKey langKey = SummaryKey.builder()
                .period(period).date(date).source(source).category(category).language(language).build();
        if (!force && summaryRepository.existsSummary(langKey)) {
            log.info("[report] cache hit, skip node key={}", langKey);
            return summaryRepository.loadSummary(langKey);
        }

        log.info("[report] compute leaf node key={} items={}", langKey, fr.getItems().size());
        List<NodeSummary> itemNodes = new ArrayList<>();
        for (HotItem item : fr.getItems()) {
            itemNodes.add(summarizeItem(period, date, source, category, language, item, force));
        }

        // 聚合 ITEM 节点为 LANGUAGE 节点（与其他中间层一致，MID_MAX_CHARS）
        return aggregate(period, date, source, category, language, itemNodes, LevelEnum.LANGUAGE, force);
    }

    /**
     * 逐篇（ITEM）节点：单篇正文直接喂 AI 生成摘要（D4：不截断）。
     * 正文为空（PENDING/下载失败）时，用「标题 + 元数据摘要」拼伪正文再尝试 AI（A+C）；
     * 完全无内容则兜底 "(无正文，跳过 AI)"，但仍创建 ITEM 节点保证树完整、可重试。
     */
    private NodeSummary summarizeItem(PeriodEnum period, LocalDate date, DataSourceEnum source,
                                      String category, String language, HotItem item, boolean force) throws IOException {
        SummaryKey itemKey = SummaryKey.builder()
                .period(period).date(date).source(source).category(category).language(language)
                .itemId(item.getId()).build();
        if (!force && summaryRepository.existsSummary(itemKey)) {
            log.info("[report] cache hit, skip item key={}", itemKey);
            return summaryRepository.loadSummary(itemKey);
        }

        log.info("[report] compute item node key={} title={}", itemKey, item.getTitle());
        String content = fetchResultRepository.loadContent(item.getContentPath());

        String inputText;
        if (content != null && !content.isBlank()) {
            inputText = content; // D4：逐篇不截断
        } else {
            // A+C：正文缺失，用标题 + 元数据摘要拼伪正文尝试一次 AI
            StringBuilder pseudo = new StringBuilder();
            if (item.getTitle() != null && !item.getTitle().isBlank()) {
                pseudo.append("# ").append(item.getTitle()).append("\n");
            }
            if (item.getSummary() != null && !item.getSummary().isBlank()) {
                pseudo.append(item.getSummary()).append("\n");
            }
            inputText = pseudo.length() == 0 ? null : pseudo.toString();
        }

        String summary;
        if (inputText == null) {
            summary = "(无正文，跳过 AI)"; // A：兜底
        } else {
            int maxChars = clamp((int) Math.round(ITEM_SCALE * inputText.length()), ITEM_MIN_CHARS, ITEM_MAX_CHARS);
            SummaryContext ctx = SummaryContext.builder().level(LevelEnum.ITEM).maxChars(maxChars).build();
            summary = reportGenerator.summarizeItem(ctx, inputText);
        }

        NodeSummary.ItemSummary itemSummary = NodeSummary.ItemSummary.builder()
                .id(item.getId()).title(item.getTitle()).url(item.getUrl()).summary(summary).build();
        NodeSummary node = NodeSummary.builder()
                .period(period).source(source).date(date).level(LevelEnum.ITEM)
                .category(category).language(language).itemId(item.getId())
                .children(new ArrayList<>()).items(List.of(itemSummary)).summary(summary).build();
        summaryRepository.saveSummary(itemKey, node);
        return node;
    }

    /**
     * 聚合（category/source/date）节点：汇总子节点 summary → 调 LLM 或 D10 copy → 组装落盘。
     */
    private NodeSummary aggregate(PeriodEnum period, LocalDate date, DataSourceEnum source,
                                  String category, String language, List<NodeSummary> children,
                                  LevelEnum level, boolean force) throws IOException {
        SummaryKey key = buildKey(period, date, source, category, language, level);
        if (!force && summaryRepository.existsSummary(key)) {
            log.info("[report] cache hit, skip node key={}", key);
            return summaryRepository.loadSummary(key);
        }

        log.info("[report] compute aggregate node key={} level={} children={}", key, level, children.size());
        // D10 copy：非叶子层且唯一子节点的「被折叠段」为 _ 占位 → 直接 copy，不调 LLM
        if (level != LevelEnum.ITEM && children.size() == 1 && isPlaceholderChild(level, children.get(0))) {
            SummaryKey childKey = childSummaryKey(period, date, source, category, language, level, children.get(0));
            summaryRepository.copySummary(childKey, key);
            return summaryRepository.loadSummary(key);
        }

        StringBuilder sb = new StringBuilder();
        List<String> childPaths = new ArrayList<>();
        for (NodeSummary child : children) {
            childPaths.add(child.getPath());
            sb.append("## [").append(child.getLevel().getCode());
            if (child.getCategory() != null) {
                sb.append(" category=").append(child.getCategory());
            }
            if (child.getLanguage() != null) {
                sb.append(" language=").append(child.getLanguage());
            }
            sb.append("]\n").append(child.getSummary()).append("\n\n");
        }

        int maxChars = (level == LevelEnum.DATE) ? REPORT_MAX_CHARS : MID_MAX_CHARS; // D3
        SummaryContext ctx = SummaryContext.builder().level(level).maxChars(maxChars).build();
        String summary = reportGenerator.summarizeNode(ctx, sb.toString());

        NodeSummary node = NodeSummary.builder()
                .period(period).source(source).date(date).level(level)
                .category(category).language(language)
                .children(childPaths).summary(summary).build();
        summaryRepository.saveSummary(key, node);
        return node;
    }

    /**
     * 判定某非叶子层（level）下唯一子节点 child 的「被折叠段」是否为 _ 占位。
     * - CATEGORY：折叠 language 段
     * - SOURCE：折叠 category 段
     * - DATE：折叠 source 段（code）
     * - LANGUAGE：折叠 itemId 段（实际不会触发，保留以贴合「除叶子外均适用」）
     */
    private boolean isPlaceholderChild(LevelEnum level, NodeSummary child) {
        return switch (level) {
            case CATEGORY -> PLACEHOLDER.equals(child.getLanguage());
            case SOURCE -> child.getCategory() != null && PLACEHOLDER.equals(child.getCategory());
            case DATE -> child.getSource() != null && PLACEHOLDER.equals(child.getSource().getCode());
            case LANGUAGE -> child.getItemId() != null && PLACEHOLDER.equals(child.getItemId());
            default -> false; // ITEM
        };
    }

    /**
     * 由父层 level 推导唯一子节点 child 对应的 SummaryKey（用于 D10 copy 的源定位）。
     */
    private SummaryKey childSummaryKey(PeriodEnum period, LocalDate date, DataSourceEnum source,
                                       String category, String language, LevelEnum level, NodeSummary child) {
        SummaryKey.SummaryKeyBuilder builder = SummaryKey.builder().period(period).date(date);
        switch (level) {
            case CATEGORY -> builder.source(source).category(category).language(child.getLanguage());
            case SOURCE -> builder.source(source).category(child.getCategory());
            case DATE -> builder.source(child.getSource());
            case LANGUAGE -> builder.source(source).category(category).language(language).itemId(child.getItemId());
            default -> { } // ITEM：不会进入
        }
        return builder.build();
    }

    /**
     * 由层级推导 SummaryKey 各段（上层段留 null，见 SummaryKey.level() 约定）。
     */
    private SummaryKey buildKey(PeriodEnum period, LocalDate date, DataSourceEnum source,
                                 String category, String language, LevelEnum level) {
        String lang = (level == LevelEnum.CATEGORY) ? null : language;
        String cat = (level == LevelEnum.SOURCE || level == LevelEnum.DATE) ? null : category;
        DataSourceEnum src = (level == LevelEnum.DATE) ? null : source;
        return SummaryKey.builder().period(period).date(date).source(src).category(cat).language(lang).build();
    }

    private static int clamp(int value, int min, int max) {
        return Math.min(max, Math.max(min, value));
    }
}
