package com.waic.springaidemo.common.entity;

import com.waic.springaidemo.common.enums.DataSourceEnum;
import com.waic.springaidemo.common.enums.LevelEnum;
import com.waic.springaidemo.common.enums.PeriodEnum;
import org.springframework.util.StringUtils;

import java.time.LocalDate;

public record CrawlCoordinate(
        PeriodEnum period,
        LocalDate date,
        DataSourceEnum source,
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
     * 读出本坐标在指定层级上的维度值（下钻分组的读半边）：
     * SOURCE 取枚举 code；CATEGORY/LANGUAGE 取字段值。
     * 仅对 SOURCE/CATEGORY/LANGUAGE 有效，其余层级（叶子/顶层）属调用方误用，抛异常。
     * 注：与 {@link SummaryCoordinate#child} 构成正反映射，改动须同步。
     */
    public String dimValue(LevelEnum level) {
        return switch (level) {
            case SOURCE -> source().getCode();
            case CATEGORY -> category();
            case LANGUAGE -> language();
            default -> throw new IllegalStateException("cannot read dimension for level: " + level);
        };
    }
}
