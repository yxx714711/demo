package com.waic.springaidemo.common.entity;

import com.waic.springaidemo.common.enums.DataSourceEnum;
import com.waic.springaidemo.common.enums.PeriodEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 通用热门项
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HotItem {

    /**
     * 唯一标识，建议使用平台 + 项目/文章ID
     */
    private String id;

    /**
     * 标题
     */
    private String title;

    /**
     * 链接
     */
    private String url;

    /**
     * 数据源
     */
    private DataSourceEnum source;

    /**
     * 周期
     */
    private PeriodEnum period;

    /**
     * 分类，不存在时用 _ 占位
     */
    private String category;

    /**
     * 语言，不存在时用 _ 占位
     */
    private String language;

    /**
     * 摘要
     */
    private String summary;

    /**
     * 内容文件路径（README 或文章正文）
     */
    private String contentPath;

    /**
     * 平台原始元数据，如 star 数、阅读量等
     */
    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();

    /**
     * 抓取时间
     */
    private LocalDateTime fetchedAt;
}
