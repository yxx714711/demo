package com.waic.springaidemo.pipeline.controller;

import com.waic.springaidemo.common.entity.FetchResult;
import com.waic.springaidemo.common.enums.PeriodEnum;
import com.waic.springaidemo.pipeline.service.PipelineService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;

/**
 * Pipeline 控制器
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class PipelineController {

    private final PipelineService pipelineService;

    /**
     * 手动触发抓取
     *
     * @param period 周期，可选 daily/weekly/monthly
     * @param date   日期，默认今天
     * @return 抓取结果
     * @throws IOException IO 异常
     */
    @PostMapping("/pipeline/crawl/{period}")
    public List<FetchResult> crawl(@PathVariable String period,
                                   @RequestParam(required = false)
                                   @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) throws IOException {
        if (date == null) {
            date = LocalDate.now();
        }
        return pipelineService.runCrawl(date, PeriodEnum.of(period));
    }

    /**
     * 手动触发每日报告生成
     *
     * @param date 日期，默认今天
     * @return 报告文件路径
     * @throws IOException IO 异常
     */
    @PostMapping("/report/daily")
    public String generateDailyReport(@RequestParam(required = false)
                                        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) throws IOException {
        if (date == null) {
            date = LocalDate.now();
        }
        return pipelineService.generateDailyReport(date);
    }
}
