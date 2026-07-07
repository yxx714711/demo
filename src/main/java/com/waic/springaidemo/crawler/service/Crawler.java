package com.waic.springaidemo.crawler.service;

import com.waic.springaidemo.common.entity.FetchResult;
import com.waic.springaidemo.common.enums.PeriodEnum;
import com.waic.springaidemo.crawler.entity.CrawlerContext;

import java.time.LocalDate;
import java.util.List;

/**
 * 数据源抓取器接口
 */
public interface Crawler {

    /**
     * 判断当前抓取器是否支持该数据源
     *
     * @param context 抓取上下文
     * @return true 表示支持
     */
    boolean supports(CrawlerContext context);

    /**
     * 执行抓取
     *
     * @param context 抓取上下文
     * @return 抓取结果
     */
    FetchResult crawl(CrawlerContext context);

    /**
     * 获取该抓取器下所有需要执行的上下文组合
     *
     * @param date   日期
     * @param period 周期
     * @return 上下文列表
     */
    List<CrawlerContext> buildContexts(LocalDate date, PeriodEnum period);
}
