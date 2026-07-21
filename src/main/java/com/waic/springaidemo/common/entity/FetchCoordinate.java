package com.waic.springaidemo.common.entity;

import com.waic.springaidemo.common.enums.DataSourceEnum;
import com.waic.springaidemo.common.enums.PeriodEnum;
import org.springframework.util.StringUtils;

import java.time.LocalDate;

/**
 * 抓取切片坐标：唯一确定一次抓取或一份 hotitems.json 的维度。
 * <p>由 period + date + source + category + language 组成。category/language 对某个
 * 数据源不适用时保持为 {@code null}（如 GitHub 无 category、掘金无 language），不再用占位符；
 * 与文件路径、摘要树约定保持一致：未指定的维度既不进内存值，也不生成对应目录段。</p>
 */
public record FetchCoordinate(
        PeriodEnum period,
        LocalDate date,
        DataSourceEnum source,
        String category,
        String language
) {

    public FetchCoordinate {
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
