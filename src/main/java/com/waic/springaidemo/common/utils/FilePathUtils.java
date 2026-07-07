package com.waic.springaidemo.common.utils;

import com.waic.springaidemo.common.enums.DataSourceEnum;
import com.waic.springaidemo.common.enums.PeriodEnum;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;

/**
 * 文件路径工具类
 */
public final class FilePathUtils {

    private static final String DATA_DIR = "data";
    private static final String CONTENT_DIR = "content";
    private static final String HOTITEMS_FILE = "hotitems.json";
    private static final String DEFAULT_PLACEHOLDER = "_";

    private FilePathUtils() {
    }

    /**
     * 获取 hotitems.json 目录
     */
    public static Path getHotItemsDir(DataSourceEnum source, PeriodEnum period, LocalDate date,
                                       String category, String language) {
        return Paths.get(DATA_DIR, source.getCode(), period.getCode(), date.toString(),
                defaultIfBlank(category), defaultIfBlank(language));
    }

    /**
     * 获取 hotitems.json 文件路径
     */
    public static Path getHotItemsFilePath(DataSourceEnum source, PeriodEnum period, LocalDate date,
                                           String category, String language) {
        return getHotItemsDir(source, period, date, category, language).resolve(HOTITEMS_FILE);
    }

    /**
     * 获取内容文件路径，与 hotitems.json 同级
     */
    public static Path getContentFilePath(DataSourceEnum source, PeriodEnum period, LocalDate date,
                                          String category, String language, String slug) {
        return getHotItemsDir(source, period, date, category, language).resolve(slug + ".md");
    }

    /**
     * 生成报告文件路径
     */
    public static Path getReportFilePath(String reportType, LocalDate date) {
        return Paths.get("reports", reportType, date.toString() + ".md");
    }

    private static String defaultIfBlank(String value) {
        return (value == null || value.isBlank()) ? DEFAULT_PLACEHOLDER : value;
    }
}
