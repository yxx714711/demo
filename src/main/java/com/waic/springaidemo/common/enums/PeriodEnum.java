package com.waic.springaidemo.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 周期枚举
 */
@Getter
@RequiredArgsConstructor
public enum PeriodEnum {

    DAILY("daily", "每日"),
    WEEKLY("weekly", "每周"),
    MONTHLY("monthly", "每月");

    private final String code;
    private final String name;

    public static PeriodEnum of(String code) {
        for (PeriodEnum period : values()) {
            if (period.code.equalsIgnoreCase(code)) {
                return period;
            }
        }
        throw new IllegalArgumentException("Unknown period: " + code);
    }
}
