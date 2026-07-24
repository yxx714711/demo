package com.waic.springaidemo.pipeline.controller;

import com.waic.springaidemo.common.entity.CrawlCoordinate;
import com.waic.springaidemo.common.entity.CrawlResult;
import com.waic.springaidemo.common.entity.SummaryCoordinate;
import com.waic.springaidemo.common.entity.SummaryResult;
import com.waic.springaidemo.common.enums.DataSourceEnum;
import com.waic.springaidemo.common.enums.PeriodEnum;
import com.waic.springaidemo.pipeline.service.PipelineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;

/**
 * Pipeline 控制器
 * @author 10542
 */
@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class PipelineController {

    private final PipelineService pipelineService;

    /**
     * 手动触发抓取：全量或指定数据源。
     * <p>period 必填；source 可选，缺省表示全量（所有数据源）；date 可选，默认今天。
     * 例：/api/pipeline/crawl?period=daily&source=github&date=2026-07-21</p>
     *
     * @param period 周期，必填 daily/weekly/monthly
     * @param source 数据源，可选 github/gitee/juejin；缺省表示全量
     * @param date   日期，可选，默认今天
     * @param force  是否强制重抓，可选，默认 false；true 时无视已有 hotitems.json 覆盖重抓
     * @return 抓取结果列表
     * @throws IOException IO 异常
     */
    @PostMapping("/pipeline/crawl")
    public List<CrawlResult> crawl(@RequestParam String period,
                                   @RequestParam(required = false) String source,
                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                   @RequestParam(required = false, defaultValue = "false") boolean force) throws IOException {
        if (date == null) {
            date = LocalDate.now();
        }
        DataSourceEnum resolvedSource = DataSourceEnum.ofNullable(source);
        CrawlCoordinate coordinate = new CrawlCoordinate(PeriodEnum.of(period), date, resolvedSource, null, null);
        return pipelineService.runCrawl(coordinate, force);
    }

    /**
     * 手动触发聚合生成报告：递归后序遍历 data 树，逐节点调 LLM + 落盘，返回顶层 summaries 节点。
     * <p>period 必填；date 可选，默认今天；force 可选，默认 false（true=忽略已有 summary 全量重算）。
     * source/category/language/itemId 在顶层恒为 null（顶层必为 date 层坐标），故不暴露 source 参数。
     * 例：/api/pipeline/generate?period=daily&date=2026-07-21</p>
     *
     * @param period 周期，必填 daily/weekly/monthly
     * @param date   日期，可选，默认今天
     * @param force  是否强制重算，可选，默认 false；true 时无视已有 summary.json 全量重算
     * @return 顶层 summaries 节点（含派生 coordinate / summary）
     * @throws IOException IO 异常（无匹配数据时由服务层抛 IllegalStateException，冒泡为 500）
     */
    @PostMapping("/pipeline/generate")
    public SummaryResult generate(@RequestParam String period,
                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                  @RequestParam(required = false, defaultValue = "false") boolean force) throws IOException {
        if (date == null) {
            date = LocalDate.now();
        }
        SummaryCoordinate coordinate = SummaryCoordinate.top(PeriodEnum.of(period), date);
        return pipelineService.runGenerate(coordinate, force);
    }
}
