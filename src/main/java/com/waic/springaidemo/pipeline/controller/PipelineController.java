package com.waic.springaidemo.pipeline.controller;

import com.waic.springaidemo.common.entity.CrawlCoordinate;
import com.waic.springaidemo.common.entity.CrawlResult;
import com.waic.springaidemo.common.enums.DataSourceEnum;
import com.waic.springaidemo.common.enums.PeriodEnum;
import com.waic.springaidemo.pipeline.service.PipelineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
        CrawlCoordinate coordinate = new CrawlCoordinate(resolvedSource, PeriodEnum.of(period), date, null, null);
        return pipelineService.runCrawl(coordinate, force);
    }

    /**
     * 组合任务触发：抓取 + 汇总生成日报（异步）。
     * <p>立即返回，不阻塞等待：任务进行中（RUNNING）返回 409 阻止重复触发；
     * 其余状态（首次/SUCCESS 再触发/失败续跑）返回 202 并在后台启动。日期固定为当天。</p>
     *
     * @param period 周期，可选 daily/weekly/monthly，默认 daily
     * @return 202 Accepted 已启动；409 Conflict 任务进行中
     */
    @PostMapping("/pipeline/run")
    public ResponseEntity<Void> runPipeline(@RequestParam(required = false, defaultValue = "daily") String period) {
        PipelineService.PipelineTriggerStatus status = pipelineService.triggerPipeline(PeriodEnum.of(period));
        if (status == PipelineService.PipelineTriggerStatus.REJECTED_RUNNING) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }
}
