package com.waic.springaidemo.persistence.service;

import com.waic.springaidemo.common.entity.CrawlCoordinate;
import com.waic.springaidemo.common.entity.CrawlResult;
import com.waic.springaidemo.common.entity.HotItem;
import com.waic.springaidemo.common.enums.PeriodEnum;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;

/**
 * 原始抓取结果持久化（hotitems.json + markdown 正文）。
 * 与聚合树持久化解耦，见 {@link SummaryRepository}。
 */
public interface CrawlRepository {

    /**
     * 保存抓取结果
     */
    void saveItems(CrawlResult result) throws IOException;

    /**
     * 批量更新已持久化 FetchResult 中 HotItem 的 contentPath
     */
    void updateItems(CrawlResult result) throws IOException;

    /**
     * 读取指定坐标下的抓取结果。category/language 为 null 时跨该维度 glob；source 必填。
     * 等价于原 loadByDate(source, period, date)。
     */
    List<CrawlResult> loadItems(CrawlCoordinate coordinate) throws IOException;

    /**
     * 读取某周期/日期下所有数据源的 hotitems.json（跨所有 source glob）。
     * 等价于原 loadAllByDate(period, date)。
     */
    List<CrawlResult> loadItems(PeriodEnum period, LocalDate date) throws IOException;

    /**
     * 保存单篇正文内容并回填 {@link com.waic.springaidemo.common.entity.HotItem#setContentPath(String)}。
     * <p>落盘路径由 {@code coordinate} + {@code item.getId()} 派生（与 hotitems.json 同目录，使用
     * normalizedCategory/normalizedLanguage）。正文为空串时标记 {@code HotItem.CONTENT_NOT_FOUND} 且不写文件。</p>
     *
     * @param coordinate 抓取坐标（提供 source/period/date/category/language）
     * @param item       热门项（提供 id 派生文件名，并回填 contentPath）
     * @param text       正文文本（人类可读）
     */
    void saveContent(CrawlCoordinate coordinate, HotItem item, String text) throws IOException;

    /**
     * 读取 Markdown 内容文件
     */
    String loadContent(String contentPath) throws IOException;
}
