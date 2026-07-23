package com.waic.springaidemo.persistence.utils;

import com.waic.springaidemo.common.entity.CrawlCoordinate;
import com.waic.springaidemo.common.entity.SummaryCoordinate;
import org.springframework.util.StringUtils;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 文件路径工具类
 */
public final class FilePathUtil {

    private static final String DATA_DIR = "data";
    public static final String HOTITEMS_FILE = "hotitems.json";

    private static final String SUMMARIES_DIR = "summaries";
    private static final String SUMMARY_FILE = "summary.json";

    private FilePathUtil() {
    }

    /**
     * 获取某抓取坐标（CrawlCoordinate）对应的存储目录，同时承载 hotitems.json 与正文 {itemId}.md。
     * 布局：data/{source}/{period}/{date}/{category}/{language}/
     * 与 summary 树不同：category/language 维度为 null 时规约为 "all" 段（不省略），
     * 保证未指定维度也有确定路径段、磁盘布局稳定。
     */
    public static Path getCrawlDir(CrawlCoordinate coordinate) {
        List<String> parts = new ArrayList<>();
        parts.add(DATA_DIR);
        parts.add(coordinate.source().getCode());
        parts.add(coordinate.period().getCode());
        parts.add(coordinate.date().toString());
        parts.add(coordinate.normalizedCategory());
        parts.add(coordinate.normalizedLanguage());
        return Paths.get(parts.get(0), parts.subList(1, parts.size()).toArray(new String[0]));
    }

    /**
     * 获取 hotitems.json 文件路径
     */
    public static Path getHotItemsJsonPath(CrawlCoordinate coordinate) {
        return getCrawlDir(coordinate).resolve(HOTITEMS_FILE);
    }

    /**
     * 获取内容文件路径，与 hotitems.json 同级。
     * itemId 为原始值，由本方法内部规整为合法路径段（与 getSummaryPath 的 itemId 规约同源）。
     */
    public static Path getContentFilePath(CrawlCoordinate coordinate, String itemId) {
        if (!StringUtils.hasText(itemId)) {
            throw new IllegalArgumentException("itemId must not be blank for content file path");
        }
        return getCrawlDir(coordinate).resolve(sanitizeItemId(itemId) + ".md");
    }

    /**
     * 推导 summaries 树中某节点的 summary.json 路径（逐维独立：维度为 null/空白则不生成对应段）。
     * 布局：summaries/{period}/{date}/{source}/{category?}/{language?}/{itemId?}/summary.json
     * 仅当对应维度非 null/空白时才追加该段，故 GitHub（无 category）路径不含 category 段、
     * 掘金（无 language）路径不含 language 段，未指定维度不会凭空生成 "_" 目录。
     * 注意：hotitems 树（getCrawlDir）对 null 维度规约为 "all" 段，与本条规约不同，勿混用。
     */
    public static Path getSummaryPath(SummaryCoordinate coordinate) {
        List<String> parts = new ArrayList<>();
        parts.add(SUMMARIES_DIR);
        parts.add(coordinate.period().getCode());
        parts.add(coordinate.date().toString());
        if (coordinate.source() != null) {
            parts.add(coordinate.source().getCode());
        }
        if (StringUtils.hasText(coordinate.category())) {
            parts.add(coordinate.category());
        }
        if (StringUtils.hasText(coordinate.language())) {
            parts.add(coordinate.language());
        }
        if (StringUtils.hasText(coordinate.itemId())) {
            parts.add(sanitizeItemId(coordinate.itemId()));
        }
        return Paths.get(parts.get(0), parts.subList(1, parts.size()).toArray(new String[0]))
                .resolve(SUMMARY_FILE);
    }

    /**
     * 规整 itemId 为合法路径段：保留字母数字及 - _ .，其余替换为 _。
     * 保证 SummaryCoordinate / content 路径一致、缓存可命中（itemId 可能含 / : # 等非法字符）。
     * 调用方已确保 itemId 非空，故此处只做字符清洗。
     */
    public static String sanitizeItemId(String itemId) {
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
}
