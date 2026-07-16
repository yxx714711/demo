package com.waic.springaidemo.common.entity;

import com.waic.springaidemo.common.enums.DataSourceEnum;
import com.waic.springaidemo.common.enums.PeriodEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * 抓取请求规格（crawl 的输入），与 {@link FetchResult} 成对：
 * 一个 FetchRequest 经 crawl 产生一个 FetchResult。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FetchRequest {

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
     * 分类，不存在时用 _ 占位
     */
    private String category;

    /**
     * 语言，不存在时用 _ 占位
     */
    private String language;
}
