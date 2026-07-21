package com.waic.springaidemo.common.utils;

import com.waic.springaidemo.common.entity.SummaryKey;
import com.waic.springaidemo.common.enums.DataSourceEnum;
import com.waic.springaidemo.common.enums.PeriodEnum;
import org.springframework.util.StringUtils;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 文件路径工具类
 */
public final class FilePathUtils {

    private static final String DATA_DIR = "data";
    private static final String HOTITEMS_FILE = "hotitems.json";
    private static final String SUMMARIES_DIR = "summaries";
    private static final String SUMMARY_FILE = "summary.json";

    private FilePathUtils() {
    }

    /**
     * 获取 hotitems.json 目录
     */
    public static Path getHotItemsDir(DataSourceEnum source, PeriodEnum period, LocalDate date,
                                       String category, String language) {
        List<String> parts = new ArrayList<>();
        parts.add(DATA_DIR);
        parts.add(source.getCode());
        parts.add(period.getCode());
        parts.add(date.toString());
        if (StringUtils.hasText(category)) {
            parts.add(category);
        }
        if (StringUtils.hasText(language)) {
            parts.add(language);
        }
        return Paths.get(parts.get(0), parts.subList(1, parts.size()).toArray(new String[0]));
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
     * 推导 summaries 树中某节点的 summary.json 路径（逐维独立：维度为 null/空白则不生成对应段）。
     * 布局：summaries/{period}/{date}/{source}/{category?}/{language?}/{itemId?}/{chunkId?}/summary.json
     * 仅当对应维度非 null/空白时才追加该段，故 GitHub（无 category）路径不含 category 段、
     * 掘金（无 language）路径不含 language 段，未指定维度不会凭空生成 "_" 目录。
     */
    public static Path getSummaryPath(SummaryKey key) {
        List<String> parts = new ArrayList<>();
        parts.add(SUMMARIES_DIR);
        parts.add(key.getPeriod().getCode());
        parts.add(key.getDate().toString());
        if (key.getSource() != null) {
            parts.add(key.getSource().getCode());
        }
        if (StringUtils.hasText(key.getCategory())) {
            parts.add(key.getCategory());
        }
        if (StringUtils.hasText(key.getLanguage())) {
            parts.add(key.getLanguage());
        }
        if (StringUtils.hasText(key.getItemId())) {
            parts.add(sanitizeItemId(key.getItemId()));
        }
        if (StringUtils.hasText(key.getChunkId())) {
            parts.add(sanitizeChunkId(key.getChunkId()));
        }
        return Paths.get(parts.get(0), parts.subList(1, parts.size()).toArray(new String[0]))
                .resolve(SUMMARY_FILE);
    }

    /**
     * 规整 itemId 为合法路径段：保留字母数字及 - _ .，其余替换为 _。
     * 保证 SummaryKey 与路径一致、缓存可命中（itemId 可能含 / : # 等非法字符）。
     * 调用方已确保 itemId 非空，故此处只做字符清洗。
     */
    private static String sanitizeItemId(String itemId) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < itemId.length(); i++) {
            char c = itemId.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        return sb.toString();
    }

    /**
     * 规整 chunkId 为合法路径段（chunk 索引由本服务生成，形如 c0/c1…，仅做基本清洗）。
     */
    private static String sanitizeChunkId(String chunkId) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chunkId.length(); i++) {
            char c = chunkId.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        return sb.toString();
    }
}
