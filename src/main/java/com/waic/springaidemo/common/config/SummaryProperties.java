package com.waic.springaidemo.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 总结相关配置（application.yml 的 app.summary.*）。
 * <ul>
 *   <li>leaf-level：叶级切片策略（ITEM=整篇直接总结；CHUNK=先切片逐块总结再聚合）。由 pipeline 读取决定切片与否；</li>
 *   <li>chunk-max-input-chars / chunk-overlap-ratio：切片输入上限与相邻块重叠比；</li>
 *   <li>max-chars.{leaf,node,report}：各模板输出长度上限（由 PromptTemplateManager 读取并映射到模板）。</li>
 * </ul>
 */
@Component
@ConfigurationProperties(prefix = "app.summary")
@Data
public class SummaryProperties {

    private String leafLevel = "ITEM";
    private int chunkMaxInputChars = 15000;
    private double chunkOverlapRatio = 0.2;
    private MaxChars maxChars = new MaxChars();

    @Data
    public static class MaxChars {
        private int leaf = 1000;
        private int node = 2000;
        private int report = 2000;
    }
}
