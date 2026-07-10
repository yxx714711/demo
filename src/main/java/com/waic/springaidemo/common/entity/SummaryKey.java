package com.waic.springaidemo.common.entity;

import com.waic.springaidemo.common.enums.DataSourceEnum;
import com.waic.springaidemo.common.enums.LevelEnum;
import com.waic.springaidemo.common.enums.PeriodEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * summaries 树节点定位键。上层节点对应段为 null：
 * - language 层：source/category/language 均非空
 * - category 层：language 为 null
 * - source 层：category/language 为 null
 * - date 层：source/category/language 均为 null
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SummaryKey {
    private PeriodEnum period;
    private LocalDate date;
    private DataSourceEnum source;
    private String category;
    private String language;
    private String itemId;

    public LevelEnum level() {
        if (itemId != null) {
            return LevelEnum.ITEM;
        }
        if (language != null) {
            return LevelEnum.LANGUAGE;
        }
        if (category != null) {
            return LevelEnum.CATEGORY;
        }
        if (source != null) {
            return LevelEnum.SOURCE;
        }
        return LevelEnum.DATE;
    }
}
