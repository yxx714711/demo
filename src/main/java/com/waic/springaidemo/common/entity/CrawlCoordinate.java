package com.waic.springaidemo.common.entity;

import com.waic.springaidemo.common.enums.DataSourceEnum;
import com.waic.springaidemo.common.enums.PeriodEnum;
import org.springframework.util.StringUtils;

import java.time.LocalDate;

public record CrawlCoordinate(
        DataSourceEnum source,
        PeriodEnum period,
        LocalDate date,
        String category,
        String language
) {

    public CrawlCoordinate {
        // 空白串统一规约为 null：未指定维度要么是真实非空值，要么是 null，绝不留空白/占位符
        if (!StringUtils.hasText(category)) {
            category = null;
        }
        if (!StringUtils.hasText(language)) {
            language = null;
        }
    }

    /**
     * 获取规整后的分类值：未指定时返回 "all"，避免作为 Map 键或路径段时出现 null。
     */
    public String normalizedCategory() {
        return category != null ? category : "all";
    }

    /**
     * 获取规整后的语言值：未指定时返回 "all"，避免作为 Map 键或路径段时出现 null。
     */
    public String normalizedLanguage() {
        return language != null ? language : "all";
    }
}
