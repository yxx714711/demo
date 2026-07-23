package com.waic.springaidemo.common.entity;

import com.waic.springaidemo.common.enums.DataSourceEnum;
import com.waic.springaidemo.common.enums.LevelEnum;
import com.waic.springaidemo.common.enums.PeriodEnum;
import org.springframework.util.StringUtils;

import java.time.LocalDate;

/**
 * summaries 树节点定位坐标，与 {@link CrawlCoordinate} 对称：
 * crawl 用 CrawlCoordinate 定位「抓取工作单元」，汇总用 SummaryCoordinate 定位「聚合节点」。
 * <p>组合关系：{@code SummaryCoordinate} 内嵌一个 {@link CrawlCoordinate} 作为定位锚（period/date/source/category/language），
 * 再叠加聚合专属的 itemId/chunkId。这样：
 * - 抓取链路（crawler / CrawlRepository / CrawlResult）的 CrawlCoordinate 语义（null=通配符）完全不变；
 * - 空白串规约交由 CrawlCoordinate 处理 category/language，SummaryCoordinate 仅规约 itemId/chunkId。
 * <p>上层节点对应段为 null：
 * - language 层：source/category/language 均非空
 * - category 层：language 为 null
 * - source 层：category/language 为 null
 * - date 层：source/category/language 均为 null
 * - item 层：追加 itemId
 * - chunk 层：追加 itemId + chunkId
 * <p>空白串统一规约为 null（同 CrawlCoordinate），保证「未指定维度」要么是真实值、要么是 null，
 * 避免 level() 把空白串误判为指定维度。
 */
public record SummaryCoordinate(
        CrawlCoordinate base,
        String itemId,
        String chunkId
) {

    public SummaryCoordinate {
        // 聚合专属段空白串统一规约为 null；period/date/source/category/language 由 base 负责规约
        if (!StringUtils.hasText(itemId)) {
            itemId = null;
        }
        if (!StringUtils.hasText(chunkId)) {
            chunkId = null;
        }
    }

    // ===== 委托访问器：转发给 base，调用方（FilePathUtil / PipelineServiceImpl）无需改动 =====

    public PeriodEnum period() {
        return base.period();
    }

    public LocalDate date() {
        return base.date();
    }

    public DataSourceEnum source() {
        return base.source();
    }

    public String category() {
        return base.category();
    }

    public String language() {
        return base.language();
    }

    // ===== 工厂：按层级构造（避免长参位置调用，提升可读性） =====

    public static SummaryCoordinate top(PeriodEnum period, LocalDate date) {
        return new SummaryCoordinate(new CrawlCoordinate(period, date, null, null, null), null, null);
    }

    /**
     * 由完整 base（CrawlCoordinate 五维）组装一个汇总节点锚点（itemId/chunkId 为空）。
     * 层级由传入的 base 各段决定（如 language=null 即 CATEGORY 层），不限于某一层。
     * {@link #of} 按层级规约出 base 各段后复用本方法，避免重复 new。
     */
    public static SummaryCoordinate node(PeriodEnum period, LocalDate date, DataSourceEnum source,
                                         String category, String language) {
        return new SummaryCoordinate(new CrawlCoordinate(period, date, source, category, language), null, null);
    }

    public static SummaryCoordinate item(PeriodEnum period, LocalDate date, DataSourceEnum source,
                                         String category, String language, String itemId) {
        return new SummaryCoordinate(new CrawlCoordinate(period, date, source, category, language), itemId, null);
    }

    public static SummaryCoordinate chunk(PeriodEnum period, LocalDate date, DataSourceEnum source,
                                          String category, String language, String itemId, String chunkId) {
        return new SummaryCoordinate(new CrawlCoordinate(period, date, source, category, language), itemId, chunkId);
    }

    /**
     * 由层级推导坐标各段（上层段留 null，见 level() 约定）。
     */
    public static SummaryCoordinate of(PeriodEnum period, LocalDate date, DataSourceEnum source,
                                       String category, String language, LevelEnum level) {
        String lang = (level == LevelEnum.CATEGORY || level == LevelEnum.SOURCE || level == LevelEnum.DATE) ? null : language;
        String cat = (level == LevelEnum.SOURCE || level == LevelEnum.DATE) ? null : category;
        DataSourceEnum src = (level == LevelEnum.DATE) ? null : source;
        return node(period, date, src, cat, lang);
    }

    /**
     * 由坐标各段推导所属层级（与路径布局一致）。
     */
    public LevelEnum level() {
        if (chunkId != null) {
            return LevelEnum.CHUNK;
        }
        if (itemId != null) {
            return LevelEnum.ITEM;
        }
        if (language() != null) {
            return LevelEnum.LANGUAGE;
        }
        if (category() != null) {
            return LevelEnum.CATEGORY;
        }
        if (source() != null) {
            return LevelEnum.SOURCE;
        }
        return LevelEnum.DATE;
    }
}
