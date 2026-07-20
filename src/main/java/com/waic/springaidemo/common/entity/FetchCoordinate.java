package com.waic.springaidemo.common.entity;

import com.waic.springaidemo.common.enums.DataSourceEnum;
import com.waic.springaidemo.common.enums.PeriodEnum;

import java.time.LocalDate;

/**
 * 抓取切片坐标：唯一确定一次抓取或一份 hotitems.json 的维度。
 * <p>请求侧允许 category/language 为 null（表示未指定维度）；
 * 持久化侧通过 {@link #normalizedCategory()} / {@link #normalizedLanguage()}
 * 将 null/blank 统一为 "_"，与文件路径和摘要树叶子层约定保持一致。</p>
 */
public record FetchCoordinate(
        PeriodEnum period,
        LocalDate date,
        DataSourceEnum source,
        String category,
        String language
) {

    public String normalizedCategory() {
        return (category == null || category.isBlank()) ? "_" : category;
    }

    public String normalizedLanguage() {
        return (language == null || language.isBlank()) ? "_" : language;
    }
}
