package com.waic.springaidemo.persistence.service;

import com.waic.springaidemo.common.entity.FetchResult;
import com.waic.springaidemo.common.enums.DataSourceEnum;
import com.waic.springaidemo.common.enums.PeriodEnum;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;

/**
 * 持久化服务接口
 */
public interface PersistenceService {

    /**
     * 保存抓取结果
     *
     * @param result 抓取结果
     * @throws IOException IO 异常
     */
    void save(FetchResult result) throws IOException;

    /**
     * 读取指定数据源、周期、日期的所有抓取结果
     *
     * @param source 数据源
     * @param period 周期
     * @param date   日期
     * @return 抓取结果列表
     * @throws IOException IO 异常
     */
    List<FetchResult> loadByDate(DataSourceEnum source, PeriodEnum period, LocalDate date) throws IOException;

    /**
     * 读取 Markdown 内容文件
     *
     * @param contentPath 内容文件路径
     * @return Markdown 内容
     * @throws IOException IO 异常
     */
    String loadContent(String contentPath) throws IOException;

    /**
     * 保存 Markdown 报告
     *
     * @param reportType 报告类型
     * @param date       日期
     * @param content    报告内容
     * @return 保存后的文件路径
     * @throws IOException IO 异常
     */
    String saveReport(String reportType, LocalDate date, String content) throws IOException;
}
