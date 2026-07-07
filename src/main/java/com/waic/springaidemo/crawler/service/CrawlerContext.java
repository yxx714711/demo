package com.waic.springaidemo.crawler.service;

import com.waic.springaidemo.common.enums.DataSourceEnum;
import com.waic.springaidemo.common.enums.PeriodEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * 抓取上下文
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CrawlerContext {

    /**
     * 数据源
     */
    private DataSourceEnum source;

    /**
     * 周期
     */
    private PeriodEnum period;

    /**
     * 日期
     */
    private LocalDate date;

    /**
     * 分类
     */
    private String category;

    /**
     * 语言
     */
    private String language;
}
