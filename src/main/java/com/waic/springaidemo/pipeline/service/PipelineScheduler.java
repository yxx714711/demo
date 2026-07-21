package com.waic.springaidemo.pipeline.service;

import com.waic.springaidemo.common.entity.CrawlCoordinate;
import com.waic.springaidemo.common.enums.PeriodEnum;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDate;

/**
 * Pipeline 定时调度器
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PipelineScheduler {

    private final PipelineService pipelineService;

    /**
     * 每日抓取：每天 23:00 执行
     */
    @Scheduled(cron = "0 0 23 * * ?")
    public void dailyCrawl() {
        runCrawl(LocalDate.now(), PeriodEnum.DAILY);
    }

    /**
     * 每周抓取：每周五 23:10 执行
     */
    @Scheduled(cron = "0 10 23 * * 5")
    public void weeklyCrawl() {
        runCrawl(LocalDate.now(), PeriodEnum.WEEKLY);
    }

    /**
     * 每月抓取检查：每月 28-31 日 23:30 执行，仅当月最后一天真正执行
     */
    @Scheduled(cron = "0 30 23 28-31 * ?")
    public void monthlyCrawl() {
        LocalDate today = LocalDate.now();
        if (!isLastDayOfMonth(today)) {
            log.info("Today is not the last day of month, skip monthly crawl");
            return;
        }
        runCrawl(today, PeriodEnum.MONTHLY);
    }

    private void runCrawl(LocalDate date, PeriodEnum period) {
        try {
            pipelineService.runCrawl(new CrawlCoordinate(null, period, date, null, null));
        } catch (IOException e) {
            log.error("Scheduled crawl failed, date={}, period={}", date, period, e);
        }
    }

    private boolean isLastDayOfMonth(LocalDate date) {
        return date.getDayOfMonth() == date.lengthOfMonth();
    }
}
