package com.waic.springaidemo.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 数据源枚举
 * @author 10542
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
        DataSourceEnum result = ofNullable(code);
        if (result == null) {
            throw new IllegalArgumentException("Unknown data source: " + code);
        }
        return result;
    }

    public static DataSourceEnum ofNullable(String code) {
        if (code == null) {
            return null;
        }
        for (DataSourceEnum source : values()) {
            if (source.code.equalsIgnoreCase(code)) {
                return source;
            }
        }
        return null;
    }
}
