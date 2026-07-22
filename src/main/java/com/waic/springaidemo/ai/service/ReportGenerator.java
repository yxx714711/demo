package com.waic.springaidemo.ai.service;

import org.springframework.core.io.Resource;

/**
 * 报告生成器接口
 * <p>单一职责：只调 LLM 对单段文本用指定模板生成总结，返回字符串，
 * 绝不写文件、不构造 NodeSummary、不感知层级与切片。
 * 模板选择与切片编排由 pipeline 负责，本接口仅消费传入的 {@link Resource} 模板与 input。</p>
 */
public interface ReportGenerator {

    /**
     * 统一总结入口：对单段 input 用指定模板生成摘要。
     * 输出长度上限由模板对应的配置决定（见 PromptTemplateManager）。
     *
     * @param input    待总结文本（单篇正文 / 单块切片 / 已拼接的子总结）
     * @param template 提示词模板 Resource（leaf / node / report）
     * @return 总结后的字符串
     */
    String summarize(String input, Resource template);
}
