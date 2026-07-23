package com.waic.springaidemo.common.entity;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.waic.springaidemo.common.enums.LevelEnum;
import com.waic.springaidemo.persistence.utils.FilePathUtil;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 递归聚合节点总结，对应 summaries 树中某节点的 summary.json。
 * <p>坐标维度（period/source/date/category/language/itemId/chunkId）组合自 {@link SummaryCoordinate}，
 * {@code level} / {@code path} 由其派生，不重复存储——仅 {@code summary} 为必须持久化的业务字段。</p>
 * @author 10542
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SummaryResult {

    @JsonUnwrapped
    private SummaryCoordinate coordinate;

    private String summary;

    public LevelEnum level() {
        return coordinate.level();
    }

    public String path() {
        return FilePathUtil.getSummaryPath(coordinate).toString().replace("\\", "/");
    }
}
