package com.waic.springaidemo.persistence.service;

import com.waic.springaidemo.common.entity.SummaryResult;
import com.waic.springaidemo.common.entity.SummaryCoordinate;

import java.io.IOException;

/**
 * 聚合树（summary.json）持久化。
 * 与原始抓取结果解耦，见 {@link CrawlRepository}。
 * @author 10542
 */
public interface SummaryRepository {

    /**
     * 某 summary 节点是否已存在（续跑跳过判断）
     */
    boolean existsSummary(SummaryCoordinate coordinate) throws IOException;

    /**
     * 读取某 summary 节点
     */
    SummaryResult loadSummary(SummaryCoordinate coordinate) throws IOException;

    /**
     * 原子写 result 节点（temp 文件 + rename，杜绝中途崩溃致文件残缺）
     */
    void saveSummary(SummaryCoordinate coordinate, SummaryResult result) throws IOException;

    /**
     * 单层 copy：将 src 节点 summary 复制为 dst 节点（D10 单 _ 子节点优化）
     */
    void copySummary(SummaryCoordinate src, SummaryCoordinate dst) throws IOException;
}
