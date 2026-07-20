package com.waic.springaidemo.common.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import com.fasterxml.jackson.annotation.JsonUnwrapped;

/**
 * 抓取请求规格（crawl 的输入），与 {@link FetchResult} 成对：
 * 一个 FetchRequest 经 crawl 产生一个 FetchResult。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FetchRequest {

    /**
     * 抓取坐标（period + date + source + category + language）。
     */
    @JsonUnwrapped
    private FetchCoordinate coordinate;
}
