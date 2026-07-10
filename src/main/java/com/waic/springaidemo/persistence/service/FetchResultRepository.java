package com.waic.springaidemo.persistence.service;

import com.waic.springaidemo.common.entity.FetchResult;
import com.waic.springaidemo.common.enums.DataSourceEnum;
import com.waic.springaidemo.common.enums.PeriodEnum;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;

/**
 * 原始抓取结果持久化（hotitems.json + markdown 正文）。
 * 与聚合树持久化解耦，见 {@link SummaryRepository}。
 */
public interface FetchResultRepository {

    /**
     * 保存抓取结果
     */
    void save(FetchResult result) throws IOException;

    /**
     * 读取指定数据源、周期、日期的抓取结果
     */
    List<FetchResult> loadByDate(DataSourceEnum source, PeriodEnum period, LocalDate date) throws IOException;

    /**
     * 读取 Markdown 内容文件
     */
    String loadContent(String contentPath) throws IOException;

    /**
     * 批量更新已持久化 FetchResult 中 HotItem 的 contentPath
     */
    void updateItems(FetchResult result) throws IOException;

    /**
     * 加载某周期/日期下所有数据源的 hotitems.json
     */
    List<FetchResult> loadAllByDate(PeriodEnum period, LocalDate date) throws IOException;
}
