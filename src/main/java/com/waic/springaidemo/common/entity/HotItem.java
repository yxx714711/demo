package com.waic.springaidemo.common.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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
     * 正文下载状态
     */
    public static final String CONTENT_PENDING = "PENDING"; // 待下载（初始态，pipeline 阶段统一设置）
    public static final String CONTENT_NOT_FOUND = "404";   // 无正文（404 / 节点缺失 / 空正文），终态不重试

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
    private String description;

    /**
     * 内容文件路径（README 或文章正文）
     */
    private String contentPath;
}
