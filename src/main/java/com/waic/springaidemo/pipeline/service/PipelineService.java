package com.waic.springaidemo.pipeline.service;

import com.waic.springaidemo.common.entity.CrawlCoordinate;
import com.waic.springaidemo.common.entity.CrawlResult;
import com.waic.springaidemo.common.entity.SummaryResult;
import com.waic.springaidemo.common.entity.SummaryCoordinate;
import com.waic.springaidemo.common.enums.PeriodEnum;

import java.io.IOException;
import java.util.List;

/**
 * Pipeline 编排服务接口
 * @author 10542
 */
public interface PipelineService {

    /**
     * 执行抓取并持久化。
     * <p>以 {@link CrawlCoordinate} 描述抓取请求：{@code source} 为 null 表示全量（所有数据源），
     * 非 null 表示仅抓取指定数据源；{@code period}/{@code date} 必填。{@code category}/{@code language}
     * 维度本方法忽略，由 crawler 内部 {@code buildFetchCoordinates} 决定。无匹配抓取器时静默返回空列表。</p>
     *
     * <p>{@code force=false} 时按 coordinate 去重：对应 hotitems.json 已存在则跳过抓取、直接读回；
     * {@code force=true} 时无视已有文件强制重新抓取并覆盖落盘。</p>
     *
     * @param coordinate 抓取坐标（见上文语义）
     * @param force      true=忽略已有文件强制重抓；false=文件存在则跳过（防重复）
     * @return 抓取结果列表
     * @throws IOException IO 异常
     */
    List<CrawlResult> runCrawl(CrawlCoordinate coordinate, boolean force) throws IOException;

    /**
     * 递归聚合生成报告（后序遍历 data 树，逐节点调 LLM + 落盘），返回结构化结果。
     * <p>以 {@link SummaryCoordinate} 描述汇总请求：必为顶层（date 层）坐标，即 {@code SummaryCoordinate.top(period, date)}；
     * source/category/language/itemId 在顶层均为 null。无匹配数据时抛异常。</p>
     *
     * @param coordinate 顶层汇总坐标（见上文语义）
     * @param force      true=忽略已有、全量重算
     * @return 顶层 summaries 节点（含派生 path / summary，可直接取用或落盘续跑）
     * @throws IOException IO 异常
     */
    SummaryResult runGenerate(SummaryCoordinate coordinate, boolean force) throws IOException;

    /**
     * 触发组合任务
     */
    SummaryResult runPipeline(PeriodEnum period);
}
