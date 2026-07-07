package com.waic.springaidemo.ai.service;

import com.waic.springaidemo.common.entity.FetchResult;

import java.time.LocalDate;
import java.util.List;

/**
 * 报告生成器接口
 */
public interface ReportGenerator {

    /**
     * 生成每日报告
     *
     * @param date    日期
     * @param results 抓取结果列表
     * @return Markdown 格式报告内容
     */
    String generateDailyReport(LocalDate date, List<FetchResult> results);
}
