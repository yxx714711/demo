package com.waic.springaidemo.pipeline.service;

import com.waic.springaidemo.common.entity.FetchResult;
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
     * 生成每日报告
     *
     * @param date 日期
     * @return 报告文件路径
     * @throws IOException IO 异常
     */
    String generateDailyReport(LocalDate date) throws IOException;
}
