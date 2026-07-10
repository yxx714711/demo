package com.waic.springaidemo.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 递归聚合节点层级
 */
@Getter
@RequiredArgsConstructor
public enum LevelEnum {
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
}
