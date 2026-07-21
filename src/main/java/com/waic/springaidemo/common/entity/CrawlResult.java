package com.waic.springaidemo.common.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import com.fasterxml.jackson.annotation.JsonUnwrapped;

import java.util.ArrayList;
import java.util.List;

/**
 * 抓取结果，对应一个 JSON 文件的数据
 * @author 10542
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CrawlResult {

    /**
     * 抓取坐标（period + date + source + category + language）。
     */
    @JsonUnwrapped
    private CrawlCoordinate coordinate;

    /**
     * 热门项列表
     */
    @Builder.Default
    private List<HotItem> items = new ArrayList<>();
}
