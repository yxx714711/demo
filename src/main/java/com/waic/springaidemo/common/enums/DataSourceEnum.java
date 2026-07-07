package com.waic.springaidemo.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 数据源枚举
 */
@Getter
@RequiredArgsConstructor
public enum DataSourceEnum {

    GITHUB("github", "GitHub"),
    GITEE("gitee", "Gitee"),
    JUEJIN("juejin", "掘金");

    private final String code;
    private final String name;

    public static DataSourceEnum of(String code) {
        for (DataSourceEnum source : values()) {
            if (source.code.equalsIgnoreCase(code)) {
                return source;
            }
        }
        throw new IllegalArgumentException("Unknown data source: " + code);
    }
}
