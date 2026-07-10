package com.waic.springaidemo.ai.service;

import com.waic.springaidemo.ai.entity.SummaryContext;

/**
 * 报告生成器接口
 * <p>单一职责：只调 LLM 生成节点总结，返回 summary 文本，绝不写文件、不构造 NodeSummary。
 * 落盘与节点组装由 persistence / pipeline 负责。</p>
 */
public interface ReportGenerator {

    /**
     * 叶子（ITEM 级）逐篇总结：input 为单篇 markdown 正文（不截断）
     */
    String summarizeItem(SummaryContext ctx, String input);

    /**
     * 叶子（language 层）总结：input 为该目录下所有 markdown 正文拼接文本
     */
    String summarizeLeaf(SummaryContext ctx, String input);

    /**
     * 聚合层（category/source/date）总结：input 为子节点 summary 拼接文本
     */
    String summarizeNode(SummaryContext ctx, String input);

    /**
     * 连通性测试：用流式方式发一句 prompt，返回模型回复文本（不落盘、不调度 pipeline）
     */
    String ping(String prompt);
}
