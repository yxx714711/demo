package com.waic.springaidemo.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 递归聚合节点层级
 */
@Getter
@RequiredArgsConstructor
public enum LevelEnum {
    CHUNK("chunk"),
    ITEM("item"),
    LANGUAGE("language"),
    CATEGORY("category"),
    SOURCE("source"),
    DATE("date");

    private final String code;

    public static LevelEnum of(String code) {
        for (LevelEnum level : values()) {
            if (level.code.equalsIgnoreCase(code)) {
                return level;
            }
        }
        throw new IllegalArgumentException("Unknown level: " + code);
    }

    /**
     * 返回当前层级之后的下一分组层级；若无更多层级返回 null（到达叶子）。
     * <p>数据驱动：中间层 CATEGORY / LANGUAGE 是否存在，由样本叶子坐标的 category / language 维度决定。
     * 约定：同组内所有 CrawlResult 同构（缺失维度一致），故调用方取首条样本即可。
     *
     * @param hasCategory 样本叶子坐标是否真实存在 category 维度（null/空白 视作缺失）
     * @param hasLanguage 样本叶子坐标是否真实存在 language 维度（null/空白 视作缺失）
     */
    public LevelEnum nextLevel(boolean hasCategory, boolean hasLanguage) {
        return switch (this) {
            case DATE -> SOURCE; // 顶层恒按 source 分区
            case SOURCE -> hasCategory ? CATEGORY
                    : hasLanguage ? LANGUAGE
                    : null;                       // source 即叶子（缺 category/language）
            case CATEGORY -> hasLanguage ? LANGUAGE : null; // category 即叶子
            default -> null;                      // LANGUAGE/ITEM/CHUNK 都为叶子边界
        };
    }
}
