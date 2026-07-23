package com.waic.springaidemo.crawler.service;

import com.waic.springaidemo.common.entity.CrawlCoordinate;
import com.waic.springaidemo.common.entity.CrawlResult;
import com.waic.springaidemo.common.entity.HotItem;
import com.waic.springaidemo.common.enums.DataSourceEnum;
import com.waic.springaidemo.common.enums.PeriodEnum;
import com.waic.springaidemo.common.exception.ContentNotFoundException;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Stream;

/**
 * 抓取器门面：同时具备列表抓取与正文下载能力。
 *
 * <p>本接口在维度模型（source + period + category + language）基础上，
 * 提供了 {@link #supports(CrawlCoordinate)} / {@link #buildCoordinates(LocalDate, PeriodEnum)}
 * 的默认实现。子类只需实现 5 个取值器与 {@link #crawl(CrawlCoordinate)} / {@link #crawlContent(HotItem)}，
 * 无需重复维度展开逻辑。</p>
 *
 * <p>抓取与持久化解耦：{@link #crawl(CrawlCoordinate)} 产出列表元数据，
 * {@link #crawlContent(HotItem)} 仅返回正文<b>文本</b>（不含落盘），落盘与 contentPath 回填由持久化层负责。</p>
 */
public interface Crawler {

    /**
     * 列表侧：判断当前抓取器是否支持该坐标
     *
     * @param coordinate 抓取坐标
     * @return true 表示支持
     */
    default boolean supports(CrawlCoordinate coordinate) {
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
    default List<CrawlCoordinate> buildCoordinates(LocalDate date, PeriodEnum period) {
        if (!getPeriods().contains(period)) {
            return List.of();
        }
        List<String> categories = getCategories();
        List<String> languages = getLanguages();

        return (categories.isEmpty() ? Stream.of((String) null) : categories.stream())
                .flatMap(category ->
                        // 每次都基于集合重新生成 Stream，避免重复消费
                        (languages.isEmpty() ? Stream.of((String) null) : languages.stream())
                                .map(language -> new CrawlCoordinate(period, date, getDataSource(), category, language))
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
    CrawlResult crawl(CrawlCoordinate coordinate);

    /**
     * 正文侧：获取热门项的内容正文（如 README、文章正文）。
     * <p>仅负责抓取与必要的格式转换（如 HTML→Markdown）并返回<b>文本</b>，
     * 不负责落盘，也不感知文件路径。落盘与 {@code contentPath} 回填由持久化层完成。</p>
     *
     * @param item 热门项（提供 url 等定位信息）
     * @return 人类可读的正文文本
     * @throws IOException               瞬时错误（超时、连接抖动等），调用方应保持 PENDING 重试
     * @throws ContentNotFoundException   正文不存在（404 / 节点缺失 / 空正文），调用方标记 404 不重试
     */
    String crawlContent(HotItem item) throws IOException, ContentNotFoundException;

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
}
