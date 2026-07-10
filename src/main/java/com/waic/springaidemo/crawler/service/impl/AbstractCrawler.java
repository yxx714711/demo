package com.waic.springaidemo.crawler.service.impl;

import com.waic.springaidemo.common.enums.DataSourceEnum;
import com.waic.springaidemo.common.enums.PeriodEnum;
import com.waic.springaidemo.crawler.service.Crawler;
import com.waic.springaidemo.crawler.entity.CrawlerContext;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 抓取器抽象基类
 */
public abstract class AbstractCrawler implements Crawler {

    /**
     * 获取数据源
     *
     * @return 数据源枚举
     */
    protected abstract DataSourceEnum getDataSource();

    /**
     * 获取支持的周期列表
     *
     * @return 周期列表
     */
    protected abstract List<PeriodEnum> getSupportedPeriods();

    /**
     * 获取分类列表，不存在时返回空列表
     *
     * @return 分类列表
     */
    protected abstract List<String> getCategories();

    /**
     * 获取语言列表，不存在时返回空列表
     *
     * @return 语言列表
     */
    protected abstract List<String> getLanguages();

    @Override
    public boolean supports(CrawlerContext context) {
        if (context.getSource() != getDataSource()) {
            return false;
        }
        if (!getSupportedPeriods().contains(context.getPeriod())) {
            return false;
        }
        if (!matchDimension(getCategories(), context.getCategory())) {
            return false;
        }
        return matchDimension(getLanguages(), context.getLanguage());
    }

    /**
     * 判断单个维度值是否合法：
     * 维度值为 null/空 表示未指定，不约束；
     * 维度值已指定时，若 crawler 不使用该维度（allowed 为空）则不支持，否则必须在允许列表内。
     *
     * @param allowed 该 crawler 支持的维度值列表
     * @param value   待校验的维度值
     * @return 是否合法
     */
    private boolean matchDimension(List<String> allowed, String value) {
        if (value == null || value.isBlank()) {
            return true;
        }
        if (allowed.isEmpty()) {
            return false;
        }
        return allowed.contains(value);
    }

    @Override
    public List<CrawlerContext> buildContexts(LocalDate date, PeriodEnum period) {
        if (!getSupportedPeriods().contains(period)) {
            return List.of();
        }
        List<CrawlerContext> contexts = new ArrayList<>();
        List<String> categories = getCategories();
        List<String> languages = getLanguages();

        if (categories.isEmpty() && languages.isEmpty()) {
            contexts.add(buildContext(date, period, null, null));
            return contexts;
        }

        if (categories.isEmpty()) {
            for (String language : languages) {
                contexts.add(buildContext(date, period, null, language));
            }
            return contexts;
        }

        if (languages.isEmpty()) {
            for (String category : categories) {
                contexts.add(buildContext(date, period, category, null));
            }
            return contexts;
        }

        for (String category : categories) {
            for (String language : languages) {
                contexts.add(buildContext(date, period, category, language));
            }
        }
        return contexts;
    }

    private CrawlerContext buildContext(LocalDate date, PeriodEnum period, String category, String language) {
        return CrawlerContext.builder()
                .source(getDataSource())
                .period(period)
                .date(date)
                .category(category)
                .language(language)
                .build();
    }
}
