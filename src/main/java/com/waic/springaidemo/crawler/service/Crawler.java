package com.waic.springaidemo.crawler.service;

import com.waic.springaidemo.common.entity.FetchCoordinate;
import com.waic.springaidemo.common.entity.FetchRequest;
import com.waic.springaidemo.common.entity.FetchResult;
import com.waic.springaidemo.common.entity.HotItem;
import com.waic.springaidemo.common.enums.DataSourceEnum;
import com.waic.springaidemo.common.enums.PeriodEnum;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 抓取器门面：同时具备列表抓取与正文下载能力。
 *
 * <p>本接口在维度模型（source + period + category + language）基础上，
 * 提供了 {@link #supports(FetchRequest)} / {@link #buildContexts(LocalDate, PeriodEnum)}
 * 的默认实现。子类只需实现 5 个取值器与 {@link #crawl(FetchRequest)} / {@link #download(HotItem, FetchResult)}，
 * 无需重复维度展开逻辑。
 */
public interface Crawler {

    /**
     * 获取数据源
     *
     * @return 数据源枚举
     */
    DataSourceEnum getDataSource();

    /**
     * 获取支持的周期列表
     *
     * @return 周期列表
     */
    List<PeriodEnum> getSupportedPeriods();

    /**
     * 获取分类列表，不存在时返回空列表
     *
     * @return 分类列表
     */
    List<String> getCategories();

    /**
     * 获取语言列表，不存在时返回空列表
     *
     * @return 语言列表
     */
    List<String> getLanguages();

    /**
     * 列表侧：判断当前抓取器是否支持该请求
     *
     * @param request 抓取请求
     * @return true 表示支持
     */
    default boolean supports(FetchRequest request) {
        FetchCoordinate coordinate = request.getCoordinate();
        if (coordinate.source() != getDataSource()) {
            return false;
        }
        if (!getSupportedPeriods().contains(coordinate.period())) {
            return false;
        }
        if (!matchDimension(getCategories(), coordinate.category())) {
            return false;
        }
        return matchDimension(getLanguages(), coordinate.language());
    }

    /**
     * 列表侧：获取该抓取器下所有需要执行的请求组合
     *
     * @param date   日期
     * @param period 周期
     * @return 请求列表
     */
    default List<FetchRequest> buildContexts(LocalDate date, PeriodEnum period) {
        if (!getSupportedPeriods().contains(period)) {
            return List.of();
        }
        List<FetchRequest> contexts = new ArrayList<>();
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

    /**
     * 列表侧：执行抓取
     *
     * @param request 抓取请求
     * @return 抓取结果
     */
    FetchResult crawl(FetchRequest request);

    /**
     * 正文侧：下载热门项的内容文件（如 README、文章正文）。
     * 基于列表侧已落盘的 {@link FetchResult} 工作。
     *
     * @param item   热门项
     * @param result 对应的抓取结果（提供 source/period/date/category/language 用于落盘路径）
     * @throws IOException 下载失败时抛出
     */
    void download(HotItem item, FetchResult result) throws IOException;

    /**
     * 判断单个维度值是否合法：
     * 维度值为 null/空/"_" 表示未指定，不约束；
     * 维度值已指定时，若 crawler 不使用该维度（allowed 为空）则不支持，否则必须在允许列表内。
     *
     * @param allowed 该 crawler 支持的维度值列表
     * @param value   待校验的维度值
     * @return 是否合法
     */
    private boolean matchDimension(List<String> allowed, String value) {
        if (value == null || value.isBlank() || "_".equals(value)) {
            return true;
        }
        if (allowed.isEmpty()) {
            return false;
        }
        return allowed.contains(value);
    }

    private FetchRequest buildContext(LocalDate date, PeriodEnum period, String category, String language) {
        return FetchRequest.builder()
                .coordinate(new FetchCoordinate(period, date, getDataSource(), category, language))
                .build();
    }
}
