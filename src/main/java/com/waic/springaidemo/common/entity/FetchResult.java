package com.waic.springaidemo.common.entity;

import com.waic.springaidemo.common.enums.DataSourceEnum;
import com.waic.springaidemo.common.enums.PeriodEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 抓取结果，对应一个 JSON 文件的数据
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FetchResult {

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

    /**
     * 热门项列表
     */
    @Builder.Default
    private List<HotItem> items = new ArrayList<>();

    public String groupKey() {
        return String.format("%s_%s_%s_%s_%s", source.getCode(), period.getCode(), date, category, language);
    }
}
