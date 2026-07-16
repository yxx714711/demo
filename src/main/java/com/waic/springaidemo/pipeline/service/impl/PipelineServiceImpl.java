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
import com.waic.springaidemo.common.entity.FetchRequest;
import com.waic.springaidemo.common.utils.TextSplitter;
import com.waic.springaidemo.crawler.service.Crawler;
import com.waic.springaidemo.crawler.service.CrawlerRegistry;
import com.waic.springaidemo.persistence.service.FetchResultRepository;
import com.waic.springaidemo.persistence.service.SummaryRepository;
import com.waic.springaidemo.pipeline.service.PipelineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

/**
 * Pipeline 编排服务实现
 * <p>单一职责：递归后序遍历 data 树，逐节点调 {@code reportGenerator} 生成文本、
 * 组装 {@link NodeSummary} 并交持久化服务落盘。本类不调用 LLM 以外逻辑，也不直接写文件。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PipelineServiceImpl implements PipelineService {

    private static final int ITEM_MAX_CHARS = 2000;        // D3：ITEM 合并层输出上限（单篇一摘要）
    private static final int CHUNK_MAX_INPUT_CHARS = 15000;   // 单块切片输入上限（同时也是触发切片阈值）
    private static final int CHUNK_MAX_OUTPUT_CHARS = 2000;  // 单块（CHUNK）总结输出上限
    private static final double CHUNK_OVERLAP_RATIO = 0.2;   // 相邻块重叠 = 单块长度 20%
    private static final int MID_MAX_CHARS = 1000;        // D3：中间聚合层（LANGUAGE/CATEGORY/SOURCE）上限
    private static final int REPORT_MAX_CHARS = 2000;    // D3：日报上限
    private static final String PLACEHOLDER = "_";       // D9/D10：占位段（_ 子目录直接拷贝）

    private final CrawlerRegistry crawlerRegistry;
    private final FetchResultRepository fetchResultRepository;
    private final SummaryRepository summaryRepository;
    private final ReportGenerator reportGenerator;
    @Qualifier("pipelineTaskExecutor")
    private final TaskExecutor pipelineTaskExecutor;

    /**
     * 内存任务状态表：key=period（固定当天），value=该 period 的异步任务状态机。
     * 仅用于「阻止重复触发」与「失败续跑」的判定，summary 树本身已在磁盘天然支持续跑。
     * 注意：JVM 重启后此表清空（视为 IDLE），下次触发从磁盘断点续跑，安全。
     */
    private final Map<PeriodEnum, TaskState> taskStates = new ConcurrentHashMap<>();

    @Override
    public List<FetchResult> runCrawl(LocalDate date, PeriodEnum period) throws IOException {
        log.info("Running crawl pipeline for date={}, period={}", date, period);

        // Step 1: 抓取元数据（不下载内容）
        List<FetchResult> results = doCrawl(date, period, crawler -> true, null);
        return persistAndDownload(results);
    }

    @Override
    public List<FetchResult> runCrawlBySource(DataSourceEnum source, LocalDate date, PeriodEnum period) throws IOException {
        log.info("Running crawl pipeline for source={}, date={}, period={}", source, date, period);

        // Step 1: 抓取指定数据源的元数据（不下载内容）
        FetchRequest probe = FetchRequest.builder()
                .source(source).period(period).date(date).build();
        List<FetchResult> results = doCrawl(date, period, crawler -> crawler.supports(probe),
                "No crawler supports source=" + source + ", period=" + period);
        return persistAndDownload(results);
    }

    /**
     * 抓取编排：对通过 crawlerFilter 的抓取器，遍历其 buildContexts 产出的请求并执行抓取。
     * buildContexts 已保证只返回受支持的请求，故此处不再逐个做 supports 过滤。
     * 单个请求抓取失败时跳过并继续，不影响其他请求。
     * 若没有任何抓取器通过过滤且 noMatchMessage 非 null，抛出 IllegalStateException。
     */
    private List<FetchResult> doCrawl(LocalDate date, PeriodEnum period,
                                      Predicate<Crawler> crawlerFilter, String noMatchMessage) {
        List<FetchResult> results = new ArrayList<>();
        boolean anyMatched = false;
        for (Crawler crawler : crawlerRegistry.getAllCrawlers()) {
            if (!crawlerFilter.test(crawler)) {
                continue;
            }
            anyMatched = true;
            for (FetchRequest context : crawler.buildContexts(date, period)) {
                try {
                    results.add(crawler.crawl(context));
                } catch (Exception e) {
                    log.warn("Crawl failed for {} context={}, skipping",
                            crawler.getClass().getSimpleName(), context, e);
                }
            }
        }
        if (noMatchMessage != null && !anyMatched) {
            throw new IllegalStateException(noMatchMessage);
        }
        return results;
    }

    /**
     * 持久化抓取结果并逐条下载正文（Step 2 + Step 3），支持断点续跑：
     * 已落盘且 contentPath 非 PENDING 的 item 直接复用旧路径（不重复下载），
     * 仅对 contentPath 为 "PENDING" 的 item 发起下载；单条失败保持 PENDING 供续跑。
     */
    private List<FetchResult> persistAndDownload(List<FetchResult> results) throws IOException {
        if (results.isEmpty()) {
            return results;
        }
        LocalDate date = results.get(0).getDate();
        PeriodEnum period = results.get(0).getPeriod();

        // 预载已有「已下载」contentPath：key = source|category|language|itemId
        Map<String, String> existingPaths = new HashMap<>();
        Set<DataSourceEnum> sources = new java.util.LinkedHashSet<>();
        for (FetchResult fr : results) {
            sources.add(fr.getSource());
        }
        for (DataSourceEnum src : sources) {
            List<FetchResult> existing = fetchResultRepository.loadByDate(src, period, date);
            for (FetchResult ex : existing) {
                for (HotItem it : ex.getItems()) {
                    if (it.getContentPath() != null && !"PENDING".equals(it.getContentPath())) {
                        existingPaths.put(contentKey(ex, it), it.getContentPath());
                    }
                }
            }
        }

        for (FetchResult result : results) {
            for (HotItem item : result.getItems()) {
                String reused = existingPaths.get(contentKey(result, item));
                item.setContentPath(reused != null ? reused : "PENDING");
            }
            fetchResultRepository.save(result);
        }
        log.info("Saved {} fetch results (reusing existing content where available)", results.size());

        for (FetchResult result : results) {
            Crawler crawler = crawlerRegistry.resolve(result).orElse(null);
            if (crawler == null) {
                log.info("无正文下载器，跳过正文抓取 source={}", result.getSource());
                fetchResultRepository.updateItems(result);
                continue;
            }
            for (HotItem item : result.getItems()) {
                if (!"PENDING".equals(item.getContentPath())) {
                    continue; // 已下载，跳过
                }
                try {
                    crawler.download(item, result);
                } catch (IOException e) {
                    log.warn("Download failed for item {} ({}): {}", item.getId(), item.getTitle(), e.getMessage());
                    // contentPath 保持 "PENDING"，供后续重试
                }
            }
            fetchResultRepository.updateItems(result);
        }
        return results;
    }

    /**
     * 断点续跑用的 item 定位键（同 source/category/language 下按 itemId 区分）。
     */
    private static String contentKey(FetchResult fr, HotItem item) {
        return fr.getSource() + "|" + fr.getCategory() + "|" + fr.getLanguage() + "|" + item.getId();
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
     * 逐篇（ITEM）节点：正文先按 {@link TextSplitter} 切成 CHUNK（单块 ≤ CHUNK_MAX_INPUT_CHARS），
     * 每个 CHUNK 单独喂 AI 生成块摘要（CHUNK 叶子）；再把它所有 CHUNK 摘要聚合为这一篇的最终摘要。
     * 单 CHUNK 时直接复用品块摘要、跳过合并；多 CHUNK 才调 summarizeNode 合并。
     * 正文为空（PENDING/下载失败）时用「标题 + 元数据摘要」拼伪正文再切片（A+C）；
     * 完全无内容则兜底 "(无正文，跳过 AI)"，不建 CHUNK，但仍创建 ITEM 节点保证树完整、可重试。
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
            inputText = content; // D4：逐篇不截断（交给 CHUNK 层切片）
        } else {
            // A+C：正文缺失，用标题 + 元数据摘要拼伪正文尝试一次 AI
            StringBuilder pseudo = new StringBuilder();
            if (item.getTitle() != null && !item.getTitle().isBlank()) {
                pseudo.append("# ").append(item.getTitle()).append("\n");
            }
            if (item.getSummary() != null && !item.getSummary().isBlank()) {
                pseudo.append(item.getSummary()).append("\n");
            }
            inputText = pseudo.isEmpty() ? null : pseudo.toString();
        }

        String summary;
        List<String> chunkPaths = new ArrayList<>();
        if (inputText == null) {
            summary = "(无正文，跳过 AI)"; // A：兜底，不建 CHUNK
        } else {
            List<String> chunks = TextSplitter.split(inputText, CHUNK_MAX_INPUT_CHARS, CHUNK_OVERLAP_RATIO);
            if (chunks.size() == 1) {
                // 单 CHUNK：直接复用品块摘要，跳过一次合并 AI 调用
                NodeSummary chunkNode = summarizeChunk(
                        period, date, source, category, language, item, chunks.get(0), 0, force);
                chunkPaths.add(chunkNode.getPath());
                summary = chunkNode.getSummary();
            } else {
                StringBuilder merged = new StringBuilder();
                for (int i = 0; i < chunks.size(); i++) {
                    NodeSummary chunkNode = summarizeChunk(
                            period, date, source, category, language, item, chunks.get(i), i, force);
                    chunkPaths.add(chunkNode.getPath());
                    merged.append("## [").append(LevelEnum.CHUNK.getCode())
                            .append("]\n").append(chunkNode.getSummary()).append("\n\n");
                }
                SummaryContext ctx = SummaryContext.builder()
                        .level(LevelEnum.ITEM).maxChars(ITEM_MAX_CHARS).build();
                summary = reportGenerator.summarizeNode(ctx, merged.toString());
            }
        }

        NodeSummary.ItemSummary itemSummary = NodeSummary.ItemSummary.builder()
                .id(item.getId()).title(item.getTitle()).url(item.getUrl()).summary(summary).build();
        NodeSummary node = NodeSummary.builder()
                .period(period).source(source).date(date).level(LevelEnum.ITEM)
                .category(category).language(language).itemId(item.getId())
                .children(chunkPaths).items(List.of(itemSummary)).summary(summary).build();
        summaryRepository.saveSummary(itemKey, node);
        return node;
    }

    /**
     * 单块（CHUNK）叶子节点：一块正文直接喂 AI 生成块摘要（maxChars=CHUNK_MAX_OUTPUT_CHARS）。
     * 以 (itemId, chunkId) 为键独立缓存，支持 partial 续跑与 force 全量重算。
     */
    private NodeSummary summarizeChunk(PeriodEnum period, LocalDate date, DataSourceEnum source,
                                       String category, String language, HotItem item,
                                       String chunkText, int index, boolean force) throws IOException {
        String chunkId = "c" + index;
        SummaryKey chunkKey = SummaryKey.builder()
                .period(period).date(date).source(source).category(category).language(language)
                .itemId(item.getId()).chunkId(chunkId).build();
        if (!force && summaryRepository.existsSummary(chunkKey)) {
            log.info("[report] cache hit, skip chunk key={}", chunkKey);
            return summaryRepository.loadSummary(chunkKey);
        }

        log.info("[report] compute chunk key={} len={}", chunkKey, chunkText.length());
        SummaryContext ctx = SummaryContext.builder()
                .level(LevelEnum.CHUNK).maxChars(CHUNK_MAX_OUTPUT_CHARS).build();
        String chunkSummary = reportGenerator.summarizeItem(ctx, chunkText);

        NodeSummary chunkNode = NodeSummary.builder()
                .period(period).source(source).date(date).level(LevelEnum.CHUNK)
                .category(category).language(language).itemId(item.getId()).chunkId(chunkId)
                .children(new ArrayList<>()).summary(chunkSummary).build();
        summaryRepository.saveSummary(chunkKey, chunkNode);
        return chunkNode;
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

    // ===== 组合任务：抓取 + 汇总（异步 + 断点续跑 + 防重复触发） =====

    /**
     * 触发组合任务（抓取→汇总）。同步、立即返回，是否真正启动由状态机决定。
     * <ul>
     *   <li>该 period 正在 RUNNING → 拒绝（返回 REJECTED_RUNNING，控制器回 409）</li>
     *   <li>其余状态（IDLE/SUCCESS/FAILED）→ 置 RUNNING 并异步启动（控制器回 202）</li>
     * </ul>
     * 不使用 {@code @Async}（避开同 bean 自调用不异步的坑），改为手动提交到注入的有界线程池。
     */
    public PipelineService.PipelineTriggerStatus triggerPipeline(PeriodEnum period) {
        LocalDate today = LocalDate.now();
        AtomicReference<PipelineService.PipelineTriggerStatus> ref = new AtomicReference<>();
        taskStates.compute(period, (k, v) -> {
            if (v != null && v.status == TaskStatus.RUNNING) {
                ref.set(PipelineService.PipelineTriggerStatus.REJECTED_RUNNING);
                return v; // 保持 RUNNING，拒绝
            }
            ref.set(PipelineService.PipelineTriggerStatus.STARTED);
            return new TaskState(TaskStatus.RUNNING, today);
        });
        if (ref.get() == PipelineService.PipelineTriggerStatus.STARTED) {
            pipelineTaskExecutor.execute(() -> runPipeline(period, today));
        }
        return ref.get();
    }

    /**
     * 异步执行体：抓取（断点补 PENDING）→ 汇总（force=false 靠 existsSummary 续跑）。
     * 无论成败都用 finally 把状态机落到 SUCCESS / FAILED，保证不会卡死在 RUNNING。
     */
    private void runPipeline(PeriodEnum period, LocalDate date) {
        try {
            log.info("[pipeline] start period={} date={}", period, date);
            persistAndDownload(doCrawl(date, period, crawler -> true, null));
            ReportResult report = generateReport(period, date, false);
            updateState(period, TaskStatus.SUCCESS, null);
            log.info("[pipeline] done period={} date={} sourceCount={} categoryCount={} path={}",
                    period, date, report.getSourceCount(), report.getCategoryCount(), report.getPath());
        } catch (Exception e) {
            log.error("[pipeline] failed period={} date={}", period, date, e);
            updateState(period, TaskStatus.FAILED, e.getMessage());
        }
    }

    private void updateState(PeriodEnum period, TaskStatus status, String errorMessage) {
        taskStates.compute(period, (k, v) -> {
            TaskState s = (v != null) ? v : new TaskState(status, LocalDate.now());
            s.status = status;
            s.errorMessage = errorMessage;
            s.updatedAt = LocalDateTime.now();
            return s;
        });
    }

    /**
     * 单 period 的任务状态机（内存，仅用于防重复触发 + 失败续跑判定）。
     */
    private enum TaskStatus {
        RUNNING,
        SUCCESS,
        FAILED
    }

    private static final class TaskState {
        private volatile TaskStatus status;
        private volatile String errorMessage;
        private volatile LocalDate date;
        private volatile LocalDateTime updatedAt;

        TaskState(TaskStatus status, LocalDate date) {
            this.status = status;
            this.date = date;
            this.updatedAt = LocalDateTime.now();
        }
    }
}
