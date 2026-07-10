package com.waic.springaidemo.ai.entity;

import com.waic.springaidemo.common.enums.LevelEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单次总结生成的上下文（层级 + 长度上限），供 prompt 模板使用
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SummaryContext {
    private LevelEnum level;
    private int maxChars;
}
