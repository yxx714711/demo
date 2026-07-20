package com.waic.springaidemo.common.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 通用热门项
 * @author 10542
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
