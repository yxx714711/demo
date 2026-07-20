package com.waic.springaidemo.common.entity;

import com.waic.springaidemo.common.enums.DataSourceEnum;
import com.waic.springaidemo.common.enums.PeriodEnum;

import java.time.LocalDate;

/**
 * 抓取切片坐标：唯一确定一次抓取或一份 hotitems.json 的维度。
 * <p>由 period + date + source + category + language 组成，其中 category/language
 * 不存在时规范化为 "_"，与文件路径和摘要树叶子层约定保持一致。</p>
 */
public record FetchCoordinate(
        PeriodEnum period,
        LocalDate date,
        DataSourceEnum source,
        String category,
        String language
) {

    public FetchCoordinate {
        if (category == null || category.isBlank()) {
            category = "_";
        }
        if (language == null || language.isBlank()) {
            language = "_";
        }
    }
}
