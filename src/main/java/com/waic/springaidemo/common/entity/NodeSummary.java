package com.waic.springaidemo.common.entity;

import com.waic.springaidemo.common.enums.DataSourceEnum;
import com.waic.springaidemo.common.enums.LevelEnum;
import com.waic.springaidemo.common.enums.PeriodEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 递归聚合节点总结，对应 summaries 树中某节点的 summary.json
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NodeSummary {
    private PeriodEnum period;
    private DataSourceEnum source;
    private LocalDate date;
    private LevelEnum level;
    private String category;
    private String language;
    private String itemId;
    private String chunkId;
    private String path;
    @Builder.Default
    private List<String> children = new ArrayList<>();
    private String summary;
    @Builder.Default
    private List<ItemSummary> items = new ArrayList<>();

    /**
     * 底层（language 层）逐条可追溯项
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ItemSummary {
        private String id;
        private String title;
        private String url;
        private String summary;
    }
}
