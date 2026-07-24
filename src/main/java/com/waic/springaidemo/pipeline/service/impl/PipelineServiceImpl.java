package com.waic.springaidemo.pipeline.service.impl;

import com.waic.springaidemo.ai.components.PromptTemplateManager;
import com.waic.springaidemo.ai.service.ReportGenerator;
import com.waic.springaidemo.common.entity.CrawlCoordinate;
import com.waic.springaidemo.common.entity.CrawlResult;
import com.waic.springaidemo.common.entity.HotItem;
import com.waic.springaidemo.common.entity.SummaryResult;
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
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.io.IOException;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Predicate;

/**
 * Pipeline 编排服务实现
 * <p>单一职责：递归后序遍历 data 树，逐节点调 {@code reportGenerator} 生成文本、
 * 组装 {@link SummaryResult} 并交持久化服务落盘。本类不调用 LLM 以外逻辑，也不直接写文件。</p>
 * <p>汇总树层级深度由数据驱动：固定 DATE → SOURCE，中间层 CATEGORY / LANGUAGE 可能存在或缺失，
 * 文本文件始终位于 ITEM 层。同一 SOURCE 下结构一致。</p>
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
    public SummaryResult runGenerate(SummaryCoordinate coordinate, boolean force) throws IOException {
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
     * 数据驱动递归：层级深度由 CrawlCoordinate 中 category / language 是否存在决定。
     * 同一 SOURCE 下结构一致，可能出现四种形态：
     * <ol>
     *   <li>DATE → SOURCE → ITEM（category、language 均缺失）</li>
     *   <li>DATE → SOURCE → CATEGORY → ITEM（language 缺失）</li>
     *   <li>DATE → SOURCE → LANGUAGE → ITEM（category 缺失）</li>
     *   <li>DATE → SOURCE → CATEGORY → LANGUAGE → ITEM（标准结构）</li>
     * </ol>
     * 由当前层级 + 实际样本决定下一分组层级；到叶子（无更多层级）时直接处理该组全部 items。
     * 缓存：非 force 且节点已存在则直接读回（断点续跑/防重复）。
     */
    private SummaryResult build(SummaryCoordinate coordinate, List<CrawlResult> leaves, boolean force) throws IOException {
        SummaryResult cached = summaryRepository.loadSummary(coordinate);
        if (!force && cached != null) {
            log.info("[report] cache hit, skip node coordinate={}", coordinate);
            return cached;
        }
        LevelEnum nextLevel = nextLevel(coordinate.level(), leaves.get(0).getCoordinate());
        if (nextLevel == null) {
            // 叶子：本组所有 CrawlResult 的 items 都是该节点内容，逐条交给 AI 后向上聚合
            return aggregateLeaf(coordinate, leaves, force);
        }
        // 非叶子：按下一层级分区（源自 CrawlCoordinate），LinkedHashMap 保序
        Map<String, List<CrawlResult>> groups = groupBy(leaves, nextLevel);
        List<SummaryResult> children = new ArrayList<>();
        for (Map.Entry<String, List<CrawlResult>> e : groups.entrySet()) {
            children.add(build(childKey(coordinate, nextLevel, e.getKey()), e.getValue(), force));
        }
        return aggregate(coordinate, children);
    }

    /**
     * 返回当前层级之后的下一分组层级；若无更多层级返回 null（到达叶子）。
     * 约定：同组内所有 CrawlResult 同构（缺失维度一致），故取首条样本判断即可。
     */
    private LevelEnum nextLevel(LevelEnum level, CrawlCoordinate coordinate) {
        return switch (level) {
            case DATE -> LevelEnum.SOURCE; // 顶层恒按 source 分区
            case SOURCE -> hasDim(coordinate.category()) ? LevelEnum.CATEGORY
                    : hasDim(coordinate.language()) ? LevelEnum.LANGUAGE
                    : null;                       // source 即叶子（缺 category/language）
            case CATEGORY -> hasDim(coordinate.language()) ? LevelEnum.LANGUAGE : null; // category 即叶子
            default -> null;                      // LANGUAGE/ITEM 都为叶子边界
        };
    }

    /**
     * 按层级把 CrawlResult 分组。source 取枚举 code；category/language 取字段值，
     * 缺席维度（null/空白）归为 "all" 桶，保证同构组内不分裂；"all" 本身作为真实取值原样成桶。
     */
    private Map<String, List<CrawlResult>> groupBy(List<CrawlResult> leaves, LevelEnum level) {
        Map<String, List<CrawlResult>> groups = new LinkedHashMap<>();
        for (CrawlResult r : leaves) {
            CrawlCoordinate cc = r.getCoordinate();
            String k = switch (level) {
                case SOURCE -> cc.source().getCode();
                case CATEGORY -> hasDim(cc.category()) ? cc.category() : "all";
                case LANGUAGE -> hasDim(cc.language()) ? cc.language() : "all";
                default -> throw new IllegalStateException("cannot group by leaf level: " + level);
            };
            groups.computeIfAbsent(k, x -> new ArrayList<>()).add(r);
        }
        return groups;
    }

    /**
     * 由父层坐标与分区层级值推导子节点坐标。维度值若为 null/空白 视作缺失并规约为 null；
     * "all" 等真实取值原样保留（不再规约为 null，否则会与上游「全部」维度冲突）。
     */
    private SummaryCoordinate childKey(SummaryCoordinate parent, LevelEnum level, String dimValue) {
        String v = hasDim(dimValue) ? dimValue : null;
        return switch (level) {
            case SOURCE -> SummaryCoordinate.node(
                    parent.period(), parent.date(), DataSourceEnum.of(dimValue), null, null);
            case CATEGORY -> SummaryCoordinate.node(
                    parent.period(), parent.date(), parent.source(), v, null);
            case LANGUAGE -> SummaryCoordinate.node(
                    parent.period(), parent.date(), parent.source(), parent.category(), v);
            default -> throw new IllegalStateException("cannot derive child key for leaf level: " + level);
        };
    }

    /**
     * 叶子节点：遍历组内所有 CrawlResult 的 items，逐条调 {@code computeLeaf} 生成 ITEM 节点，
     * 再聚合成当前层摘要节点。
     */
    private SummaryResult aggregateLeaf(SummaryCoordinate key, List<CrawlResult> leaves, boolean force) throws IOException {
        List<SummaryResult> itemNodes = new ArrayList<>();
        for (CrawlResult r : leaves) {
            for (HotItem item : r.getItems()) {
                itemNodes.add(computeLeaf(key.period(), key.date(), key.source(), key.category(), key.language(), item, force));
            }
        }
        return aggregate(key, itemNodes);
    }

    /**
     * 叶（ITEM）节点：正文文本准备与 AI 总结解耦。
     * 正文为空（PENDING/下载失败）时用「标题 + 元数据摘要」拼伪正文再喂 AI；
     * 完全无内容则兜底 "(无正文，跳过 AI)"，但仍创建 ITEM 节点保证树完整、可重试。
     * 切片仅为喂 AI 不超限，不单独落盘 CHUNK 子节点。
     */
    private SummaryResult computeLeaf(PeriodEnum period, LocalDate date, DataSourceEnum source,
                                      String category, String language, HotItem item, boolean force) throws IOException {
        SummaryCoordinate itemKey = SummaryCoordinate.item(period, date, source, category, language, item.getId());
        SummaryResult cached = summaryRepository.loadSummary(itemKey);
        if (!force && cached != null) {
            log.info("[report] cache hit, skip item key={}", itemKey);
            return cached;
        }

        log.info("[report] compute item node key={} title={}", itemKey, item.getTitle());
        String content = crawlRepository.loadContent(item.getContentPath());
        String inputText = prepareInputText(item, content);
        String summary = (inputText == null)
                ? "(无正文，跳过 AI)"
                : summarizeLeaf(inputText);

        SummaryResult node = SummaryResult.builder()
                .coordinate(itemKey).summary(summary).build();
        summaryRepository.saveSummary(itemKey, node);
        return node;
    }

    /**
     * 准备喂给 AI 的输入文本：优先用 markdown 正文；正文缺失时用「标题 + 描述」拼伪正文兜底。
     */
    private String prepareInputText(HotItem item, String content) {
        if (content != null && !content.isBlank()) {
            return content;
        }
        StringBuilder pseudo = new StringBuilder();
        if (item.getTitle() != null && !item.getTitle().isBlank()) {
            pseudo.append("# ").append(item.getTitle()).append("\n");
        }
        if (item.getDescription() != null && !item.getDescription().isBlank()) {
            pseudo.append(item.getDescription()).append("\n");
        }
        return pseudo.isEmpty() ? null : pseudo.toString();
    }

    /**
     * 对单篇文本做 AI 总结：按配置决定整篇还是切片（性能低时先切片再聚合）。
     * CHUNK 模式下各块摘要合回 ITEM 层，不单独落盘。
     */
    private String summarizeLeaf(String inputText) {
        LevelEnum leafLevel = LevelEnum.of(summaryProperties.getLeafLevel());
        boolean chunk = leafLevel == LevelEnum.CHUNK
                && inputText.length() > summaryProperties.getChunkMaxInputChars();
        if (!chunk) {
            return reportGenerator.summarize(inputText, promptTemplateManager.leafTemplate());
        }
        List<String> chunks = TextSplitterUtil.split(
                inputText, summaryProperties.getChunkMaxInputChars(), summaryProperties.getChunkOverlapRatio());
        List<String> chunkSummaries = new ArrayList<>();
        for (String chunkText : chunks) {
            chunkSummaries.add(reportGenerator.summarize(chunkText, promptTemplateManager.leafTemplate()));
        }
        // 单块直接取该块摘要；多块聚回 ITEM 层（report 模板）
        return (chunks.size() == 1)
                ? chunkSummaries.get(0)
                : reportGenerator.summarize(String.join("\n\n", chunkSummaries), promptTemplateManager.reportTemplate());
    }

    /**
     * 聚合（source/category/language/date）节点：拼接子节点 summary → 调 LLM → 组装落盘。
     * DATE 层用 report 模板，其余用 node 模板。
     */
    private SummaryResult aggregate(SummaryCoordinate key, List<SummaryResult> children) throws IOException {
        LevelEnum level = key.level();
        log.info("[report] compute aggregate node key={} level={} children={}", key, level, children.size());

        String input = buildAggregateInput(children);
        Resource template = (level == LevelEnum.DATE)
                ? promptTemplateManager.reportTemplate()
                : promptTemplateManager.nodeTemplate();
        String summary = reportGenerator.summarize(input, template);

        SummaryResult node = SummaryResult.builder()
                .coordinate(key).summary(summary).build();
        summaryRepository.saveSummary(key, node);
        return node;
    }

    /**
     * 拼接聚合节点喂给 LLM 的输入文本：每个子节点一段，带上自身层级与 category/language 信息。
     */
    private String buildAggregateInput(List<SummaryResult> children) {
        StringBuilder sb = new StringBuilder();
        for (SummaryResult child : children) {
            SummaryCoordinate cc = child.getCoordinate();
            sb.append("## [").append(cc.level().getCode());
            if (hasDim(cc.category())) {
                sb.append(" category=").append(cc.category());
            }
            if (hasDim(cc.language())) {
                sb.append(" language=").append(cc.language());
            }
            sb.append("]\n").append(child.getSummary()).append("\n\n");
        }
        return sb.toString();
    }

    /**
     * 判定维度值是否真实存在（仅 null/空白 视作缺失；
     * 注意 "all" 是上游真实取值——如 Gitee「全部推荐项目」tab、GitHub「All」语言，不当缺失）。
     */
    private boolean hasDim(String value) {
        return value != null && !value.isBlank();
    }

    @Override
    public SummaryResult runPipeline(PeriodEnum period) {
        throw new UnsupportedOperationException("runPipeline 尚未实现");
    }
}
