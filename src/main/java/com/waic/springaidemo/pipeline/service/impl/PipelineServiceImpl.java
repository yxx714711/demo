package com.waic.springaidemo.pipeline.service.impl;

import com.waic.springaidemo.ai.components.PromptTemplateManager;
import com.waic.springaidemo.ai.service.ReportGenerator;
import com.waic.springaidemo.common.entity.CrawlCoordinate;
import com.waic.springaidemo.common.entity.CrawlResult;
import com.waic.springaidemo.common.entity.HotItem;
import com.waic.springaidemo.common.entity.NodeSummary;
import com.waic.springaidemo.common.entity.SummaryCoordinate;
import com.waic.springaidemo.common.enums.DataSourceEnum;
import com.waic.springaidemo.common.enums.LevelEnum;
import com.waic.springaidemo.common.enums.PeriodEnum;
import com.waic.springaidemo.common.exception.ContentNotFoundException;
import com.waic.springaidemo.common.config.SummaryProperties;
import com.waic.springaidemo.crawler.components.CrawlerManager;
import com.waic.springaidemo.crawler.service.Crawler;
import com.waic.springaidemo.persistence.service.CrawlRepository;
import com.waic.springaidemo.persistence.service.SummaryRepository;
import com.waic.springaidemo.pipeline.service.PipelineService;
import com.waic.springaidemo.pipeline.utils.TextSplitterUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.Resource;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
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

    private final CrawlerManager crawlerManager;
    private final CrawlRepository crawlRepository;
    private final SummaryRepository summaryRepository;
    private final ReportGenerator reportGenerator;
    private final PromptTemplateManager promptTemplateManager;
    private final SummaryProperties summaryProperties;
    @Qualifier("pipelineTaskExecutor")
    private final TaskExecutor pipelineTaskExecutor;

    /**
     * 内存任务状态表：key=period（固定当天），value=该 period 的异步任务状态机。
     * 仅用于「阻止重复触发」与「失败续跑」的判定，summary 树本身已在磁盘天然支持续跑。
     * 注意：JVM 重启后此表清空（视为 IDLE），下次触发从磁盘断点续跑，安全。
     */
    private final Map<PeriodEnum, TaskState> taskStates = new ConcurrentHashMap<>();

    @Override
    public List<CrawlResult> runCrawl(CrawlCoordinate coordinate, boolean force) throws IOException {
        DataSourceEnum source = coordinate.source();
        PeriodEnum period = coordinate.period();
        LocalDate date = coordinate.date();
        log.info("Running crawl pipeline for source={}, date={}, period={}, force={}", source, date, period, force);

        // source 为 null 表示全量（所有数据源）；否则仅匹配指定源。
        // category/language 维度本方法忽略，由 crawler 内部 buildCoordinates 决定。
        // 统一行为：无匹配抓取器时静默返回空列表（noMatchMessage=null）。
        Predicate<Crawler> crawlerFilter = source == null
                ? crawler -> true
                : crawler -> crawler.supports(coordinate);
        List<CrawlResult> results = doCrawl(date, period, crawlerFilter, force);
        return doDownload(results);
    }

    /**
     * 抓取编排（含 coordinate 级去重 + 即时落盘）：对通过 crawlerFilter 的抓取器，
     * 遍历其 buildCoordinates 产出的坐标并逐个处理：
     * <ul>
     *   <li>{@code force=false} 且对应 hotitems.json 已存在 → 跳过抓取，直接读回该 CrawlResult
     *       （其 item 携带磁盘上最新 contentPath 状态）；</li>
     *   <li>否则执行 {@code crawler.crawl()}，将 item 的 contentPath 初始化为 PENDING 后
     *       <b>立即落盘</b>（saveItems），再收集进结果。</li>
     * </ul>
     * 单个坐标抓取失败时跳过并继续，不影响其他坐标。
     * 若没有任何抓取器通过过滤，抛出 IllegalStateException。
     */
    private List<CrawlResult> doCrawl(LocalDate date, PeriodEnum period,
                                      Predicate<Crawler> crawlerFilter, boolean force) {
        List<CrawlResult> results = new ArrayList<>();
        boolean anyMatched = false;
        for (Crawler crawler : crawlerManager.getAllCrawlers()) {
            if (!crawlerFilter.test(crawler)) {
                continue;
            }
            anyMatched = true;
            for (CrawlCoordinate coordinate : crawler.buildCoordinates(date, period)) {
                // 防重复：非强刷且文件已存在，直接读回磁盘上的结果（含最新 contentPath）
                if (!force) {
                    Optional<CrawlResult> existing = crawlRepository.loadItem(coordinate);
                    if (existing.isPresent()) {
                        log.info("Skip crawl (hotitems.json exists) coordinate={}", coordinate);
                        results.add(existing.get());
                        continue;
                    }
                }
                try {
                    CrawlResult result = crawler.crawl(coordinate);
                    crawlRepository.saveItems(result);
                    results.add(result);
                } catch (Exception e) {
                    log.warn("Crawl failed for {} coordinate={}, skipping",
                            crawler.getClass().getSimpleName(), coordinate, e);
                }
            }
        }
        if (!anyMatched) {
            throw new IllegalStateException();
        }
        return results;
    }

    /**
     * 逐条下载正文（Step 3），纯 PENDING 驱动、支持断点续跑：
     * 仅对 contentPath 为 "PENDING" 的 item 发起下载，其余（真实路径 / 404）跳过；
     * 单条 IOException 保持 PENDING 供续跑，ContentNotFoundException 标记 404 不重试。
     * 每个 CrawlResult 处理完统一 updateItems 写回磁盘。
     */
    private List<CrawlResult> doDownload(List<CrawlResult> results) throws IOException {
        if (CollectionUtils.isEmpty(results)) {
            return results;
        }
        for (CrawlResult result : results) {
            Crawler crawler = crawlerManager.getCrawlerByCoordinate(result.getCoordinate()).orElse(null);
            if (crawler == null) {
                log.info("无正文下载器，标记无正文 source={}", result.getCoordinate().source());
                result.getItems().forEach(i -> i.setContentPath(HotItem.CONTENT_NOT_FOUND));
                crawlRepository.updateItems(result);
                continue;
            }
            for (HotItem item : result.getItems()) {
                if (!HotItem.CONTENT_PENDING.equals(item.getContentPath())) {
                    continue; // 已下载或已标记 404，跳过
                }
                try {
                    String text = crawler.crawlContent(item);
                    crawlRepository.saveContent(result.getCoordinate(), item, text);
                } catch (ContentNotFoundException e) {
                    item.setContentPath(HotItem.CONTENT_NOT_FOUND);
                    log.warn("Content not found for item {} ({}): {}", item.getId(), item.getTitle(), e.getMessage());
                } catch (IOException e) {
                    log.warn("Fetch content failed for item {} ({}): {}", item.getId(), item.getTitle(), e.getMessage());
                    // contentPath 保持 PENDING，供后续重试
                }
            }
            crawlRepository.updateItems(result);
        }
        return results;
    }

    @Override
    public NodeSummary runGenerate(SummaryCoordinate coordinate, boolean force) throws IOException {
        PeriodEnum period = coordinate.period();
        LocalDate date = coordinate.date();
        List<CrawlResult> results = crawlRepository.loadItems(period, date);
        log.info("[report] runGenerate start period={} date={} force={} fetchResults={}", period, date, force, results.size());
        if (results.isEmpty()) {
            log.warn("No data found for period={} date={}", period, date);
            throw new IllegalStateException("No data found for period=" + period + " date=" + date);
        }
        return build(SummaryCoordinate.top(period, date), results, force);
    }

    /**
     * 坐标驱动递归（DATE→SOURCE→CATEGORY 按 CrawlCoordinate 维度分区；LANGUAGE 为叶子边界）。
     * 缓存：非 force 且节点已存在则直接读回（断点续跑/防重复）。
     */
    private NodeSummary build(SummaryCoordinate key, List<CrawlResult> leaves, boolean force) throws IOException {
        if (!force && summaryRepository.existsSummary(key)) {
            log.info("[report] cache hit, skip node key={}", key);
            return summaryRepository.loadSummary(key);
        }
        LevelEnum level = key.level();
        if (level == LevelEnum.LANGUAGE) {
            // 叶子边界：该语言桶必为唯一 CrawlResult，遍历其 items 建 ITEM 节点 → 聚合成 LANGUAGE
            CrawlResult fr = leaves.get(0);
            log.info("[report] compute language node key={} items={}", key, fr.getItems().size());
            List<NodeSummary> itemNodes = new ArrayList<>();
            for (HotItem item : fr.getItems()) {
                itemNodes.add(computeLeaf(key.period(), key.date(), key.source(), key.category(), key.language(), item, force));
            }
            return aggregate(key, itemNodes);
        }
        // 非叶子：按当前层的下一维度分区（源自 CrawlCoordinate），LinkedHashMap 保序
        Function<CrawlCoordinate, String> dim = switch (level) {
            case DATE -> cc -> cc.source().getCode();
            case SOURCE -> CrawlCoordinate::normalizedCategory;
            case CATEGORY -> CrawlCoordinate::normalizedLanguage;
            default -> throw new IllegalStateException("unexpected grouping level: " + level);
        };
        Map<String, List<CrawlResult>> groups = new LinkedHashMap<>();
        for (CrawlResult r : leaves) {
            groups.computeIfAbsent(dim.apply(r.getCoordinate()), k -> new ArrayList<>()).add(r);
        }
        List<NodeSummary> children = new ArrayList<>();
        for (Map.Entry<String, List<CrawlResult>> e : groups.entrySet()) {
            children.add(build(childKeyOf(key, level, e.getKey()), e.getValue(), force));
        }
        return aggregate(key, children);
    }

    /**
     * 由父层坐标与分区维度值推导子节点坐标（对应 SummaryCoordinate.of 的层级约定）。
     */
    private SummaryCoordinate childKeyOf(SummaryCoordinate parent, LevelEnum parentLevel, String dimValue) {
        PeriodEnum period = parent.period();
        LocalDate date = parent.date();
        return switch (parentLevel) {
            case DATE -> SummaryCoordinate.of(period, date, DataSourceEnum.of(dimValue), null, null, LevelEnum.SOURCE);
            case SOURCE -> SummaryCoordinate.of(period, date, parent.source(), dimValue, null, LevelEnum.CATEGORY);
            case CATEGORY -> SummaryCoordinate.of(period, date, parent.source(), parent.category(), dimValue, LevelEnum.LANGUAGE);
            default -> throw new IllegalStateException("no child for level " + parentLevel);
        };
    }

    /**
     * 叶（ITEM）节点：整篇/伪正文交给 ai 统一生产；
     * CHUNK 模式下 ai 额外给出各块摘要，本方法逐块落盘作为子节点（保树结构可追溯）。
     * 正文为空（PENDING/下载失败）时用「标题 + 元数据摘要」拼伪正文再喂 AI；
     * 完全无内容则兜底 "(无正文，跳过 AI)"，不建 CHUNK，但仍创建 ITEM 节点保证树完整、可重试。
     */
    private NodeSummary computeLeaf(PeriodEnum period, LocalDate date, DataSourceEnum source,
                                    String category, String language, HotItem item, boolean force) throws IOException {
        SummaryCoordinate itemKey = SummaryCoordinate.item(period, date, source, category, language, item.getId());
        if (!force && summaryRepository.existsSummary(itemKey)) {
            log.info("[report] cache hit, skip item key={}", itemKey);
            return summaryRepository.loadSummary(itemKey);
        }

        log.info("[report] compute item node key={} title={}", itemKey, item.getTitle());
        String content = crawlRepository.loadContent(item.getContentPath());

        String inputText;
        if (content != null && !content.isBlank()) {
            inputText = content; // D4：逐篇不截断（CHUNK 模式下由 pipeline 切片）
        } else {
            // 正文缺失，用标题 + 元数据摘要拼伪正文尝试一次 AI
            StringBuilder pseudo = new StringBuilder();
            if (item.getTitle() != null && !item.getTitle().isBlank()) {
                pseudo.append("# ").append(item.getTitle()).append("\n");
            }
            if (item.getDescription() != null && !item.getDescription().isBlank()) {
                pseudo.append(item.getDescription()).append("\n");
            }
            inputText = pseudo.isEmpty() ? null : pseudo.toString();
        }

        String summary;
        if (inputText == null) {
            summary = "(无正文，跳过 AI)"; // 兜底，不建 CHUNK
        } else {
            // 切片与否由配置决定：leafLevel=CHUNK 且超阈值才切片，否则整篇直接 leaf 模板
            LevelEnum leafLevel = LevelEnum.of(summaryProperties.getLeafLevel());
            boolean chunk = leafLevel == LevelEnum.CHUNK
                    && inputText.length() > summaryProperties.getChunkMaxInputChars();
            if (chunk) {
                List<String> chunks = TextSplitterUtil.split(
                        inputText, summaryProperties.getChunkMaxInputChars(), summaryProperties.getChunkOverlapRatio());
                List<String> chunkSummaries = new ArrayList<>();
                for (String chunkText : chunks) {
                    chunkSummaries.add(reportGenerator.summarize(chunkText, promptTemplateManager.leafTemplate()));
                }
                String merged = String.join("\n\n", chunkSummaries);
                // 多块聚回 ITEM 层（report 模板，2000）；单块直接取该块摘要
                summary = (chunks.size() == 1)
                        ? chunkSummaries.get(0)
                        : reportGenerator.summarize(merged, promptTemplateManager.reportTemplate());
                for (int i = 0; i < chunkSummaries.size(); i++) {
                    String chunkId = "c" + i;
                    SummaryCoordinate chunkKey = SummaryCoordinate.chunk(
                            period, date, source, category, language, item.getId(), chunkId);
                    NodeSummary chunkNode = NodeSummary.builder()
                            .coordinate(chunkKey).summary(chunkSummaries.get(i)).build();
                    summaryRepository.saveSummary(chunkKey, chunkNode);
                }
            } else {
                summary = reportGenerator.summarize(inputText, promptTemplateManager.leafTemplate());
            }
        }

        NodeSummary node = NodeSummary.builder()
                .coordinate(itemKey).summary(summary).build();
        summaryRepository.saveSummary(itemKey, node);
        return node;
    }

    /**
     * 聚合（category/source/date/language）节点：汇总子节点 summary → 调 LLM 或 D10 copy → 组装落盘。
     */
    private NodeSummary aggregate(SummaryCoordinate key, List<NodeSummary> children) throws IOException {
        LevelEnum level = key.level();
        log.info("[report] compute aggregate node key={} level={} children={}", key, level, children.size());
        // D10 copy：非叶子层且唯一子节点的「被折叠段」未指定（null/空白）→ 直接 copy，不调 LLM
        if (level != LevelEnum.ITEM && children.size() == 1 && isPlaceholderChild(level, children.get(0))) {
            SummaryCoordinate childKey = SummaryCoordinate.childOf(
                    key.period(), key.date(), key.source(), key.category(), key.language(), level, children.get(0));
            summaryRepository.copySummary(childKey, key);
            return summaryRepository.loadSummary(key);
        }

        StringBuilder sb = new StringBuilder();
        for (NodeSummary child : children) {
            sb.append("## [").append(child.level().getCode());
            if (child.getCoordinate().category() != null) {
                sb.append(" category=").append(child.getCoordinate().category());
            }
            if (child.getCoordinate().language() != null) {
                sb.append(" language=").append(child.getCoordinate().language());
            }
            sb.append("]\n").append(child.getSummary()).append("\n\n");
        }

        Resource template = (level == LevelEnum.DATE)
                ? promptTemplateManager.reportTemplate()
                : promptTemplateManager.nodeTemplate();
        String summary = reportGenerator.summarize(sb.toString(), template);

        NodeSummary node = NodeSummary.builder()
                .coordinate(key).summary(summary).build();
        summaryRepository.saveSummary(key, node);
        return node;
    }

    /**
     * 判定某非叶子层（level）下唯一子节点 child 的「被折叠段」是否未指定（null/空白）。
     * D10：未指定维度不贡献信息，父摘要直接 copy 子摘要，跳过 LLM。
     * - CATEGORY：折叠 language 段
     * - SOURCE：折叠 category 段
     * - DATE：折叠 source 段（code，实际恒指定）
     * - LANGUAGE：折叠 itemId 段（实际不会触发，保留以贴合「除叶子外均适用」）
     */
    private boolean isPlaceholderChild(LevelEnum level, NodeSummary child) {
        return switch (level) {
            case CATEGORY -> !StringUtils.hasText(child.getCoordinate().language());
            case SOURCE -> !StringUtils.hasText(child.getCoordinate().category());
            case DATE -> child.getCoordinate().source() == null;
            case LANGUAGE -> !StringUtils.hasText(child.getCoordinate().itemId());
            default -> false; // ITEM
        };
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
    @Override
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
            doDownload(doCrawl(date, period, crawler -> true, false));
            NodeSummary report = runGenerate(SummaryCoordinate.top(period, date), false);
            updateState(period, TaskStatus.SUCCESS, null);
            log.info("[pipeline] done period={} date={} path={}",
                    period, date, report.path());
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
