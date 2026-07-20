package com.waic.springaidemo.crawler.service;

import com.waic.springaidemo.common.entity.FetchCoordinate;
import com.waic.springaidemo.common.entity.FetchResult;
import com.waic.springaidemo.common.entity.HotItem;
import com.waic.springaidemo.common.enums.DataSourceEnum;
import com.waic.springaidemo.common.enums.PeriodEnum;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Stream;

/**
 * 抓取器门面：同时具备列表抓取与正文下载能力。
 *
 * <p>本接口在维度模型（source + period + category + language）基础上，
 * 提供了 {@link #supports(FetchCoordinate)} / {@link #buildFetchCoordinates(LocalDate, PeriodEnum)}
 * 的默认实现。子类只需实现 5 个取值器与 {@link #crawl(FetchCoordinate)} / {@link #download(HotItem, FetchResult)}，
 * 无需重复维度展开逻辑。
 */
public interface Crawler {

    /**
     * 列表侧：判断当前抓取器是否支持该坐标
     *
     * @param coordinate 抓取坐标
     * @return true 表示支持
     */
    default boolean supports(FetchCoordinate coordinate) {
        if (coordinate.source() != getDataSource()) {
            return false;
        }
        if (!getPeriods().contains(coordinate.period())) {
            return false;
        }
        if (!matchDimension(getCategories(), coordinate.category())) {
            return false;
        }
        return matchDimension(getLanguages(), coordinate.language());
    }

    /**
     * 列表侧：获取该抓取器下所有需要执行的坐标组合
     *
     * @param date   日期
     * @param period 周期
     * @return 坐标列表
     */
    default List<FetchCoordinate> buildFetchCoordinates(LocalDate date, PeriodEnum period) {
        if (!getPeriods().contains(period)) {
            return List.of();
        }
        List<String> categories = getCategories();
        List<String> languages = getLanguages();

        return (categories.isEmpty() ? Stream.of((String) null) : categories.stream())
                .flatMap(category ->
                        // 每次都基于集合重新生成 Stream，避免重复消费
                        (languages.isEmpty() ? Stream.of((String) null) : languages.stream())
                                .map(language -> buildCoordinate(date, period, category, language))
                )
                .toList();
    }

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
    List<PeriodEnum> getPeriods();

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
     * 列表侧：执行抓取
     *
     * @param coordinate 抓取坐标
     * @return 抓取结果
     */
    FetchResult crawl(FetchCoordinate coordinate);

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
     * 维度值为 null/空 表示未指定，不约束；
     * 维度值已指定时，若 crawler 不使用该维度（allowed 为空）则不支持，否则必须在允许列表内。
     *
     * @param allowed 该 crawler 支持的维度值列表
     * @param value   待校验的维度值
     * @return 是否合法
     */
    private boolean matchDimension(List<String> allowed, String value) {
        if (!StringUtils.hasText(value)) {
            return true;
        }
        if (allowed.isEmpty()) {
            return false;
        }
        return allowed.contains(value);
    }

    private FetchCoordinate buildCoordinate(LocalDate date, PeriodEnum period, String category, String language) {
        return new FetchCoordinate(period, date, getDataSource(), category, language);
    }
}
