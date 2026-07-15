package com.waic.springaidemo.pipeline.service;

import com.waic.springaidemo.common.entity.FetchResult;
import com.waic.springaidemo.common.entity.ReportResult;
import com.waic.springaidemo.common.enums.DataSourceEnum;
import com.waic.springaidemo.common.enums.PeriodEnum;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;

/**
 * Pipeline 编排服务接口
 */
public interface PipelineService {

    /**
     * 执行指定周期的抓取并持久化
     *
     * @param date   日期
     * @param period 周期
     * @return 抓取结果列表
     * @throws IOException IO 异常
     */
    List<FetchResult> runCrawl(LocalDate date, PeriodEnum period) throws IOException;

    /**
     * 执行指定数据源、日期、周期的抓取并持久化
     *
     * @param source 数据源
     * @param date   日期
     * @param period 周期
     * @return 抓取结果列表
     * @throws IOException IO 异常
     */
    List<FetchResult> runCrawlBySource(DataSourceEnum source, LocalDate date, PeriodEnum period) throws IOException;

    /**
     * 递归聚合生成报告（后序遍历 data 树，逐节点调 LLM + 落盘），返回结构化结果。
     *
     * @param period 周期（daily/weekly/monthly）
     * @param date   日期
     * @param force  true=忽略已有、全量重算
     * @return 顶层 summaries 节点结果
     * @throws IOException IO 异常
     */
    ReportResult generateReport(PeriodEnum period, LocalDate date, boolean force) throws IOException;

    /**
     * 生成每日报告（便捷入口，force=false）
     */
    default ReportResult generateDailyReport(LocalDate date) throws IOException {
        return generateReport(PeriodEnum.DAILY, date, false);
    }

    /**
     * 触发组合任务（抓取→汇总），异步执行。立即返回触发结果，不阻塞等待。
     *
     * @param period 周期（daily/weekly/monthly）
     * @return STARTED=已启动（控制器回 202）；REJECTED_RUNNING=该 period 正在运行被拒绝（控制器回 409）
     */
    PipelineTriggerStatus triggerPipeline(PeriodEnum period);

    /**
     * 组合任务触发结果。
     */
    enum PipelineTriggerStatus {
        STARTED,
        REJECTED_RUNNING
    }
}
