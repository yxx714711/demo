package com.waic.springaidemo.crawler.utils;

import io.github.furstenheim.CopyDown;
import io.github.furstenheim.OptionsBuilder;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.nodes.Element;

/**
 * HTML 转 Markdown 工具类。
 * 转换前先剥离 UI 噪声（style/script/svg/代码块头部等），失败或结果为空时回退纯文本。
 */
@Slf4j
public final class HtmlToMarkdown {

    private static final CopyDown COPY_DOWN = new CopyDown(OptionsBuilder.anOptions().build());

    private HtmlToMarkdown() {
    }

    /**
     * 将 HTML 元素转换为 Markdown。
     * 转换异常或结果为空白时，回退为纯文本（element.text()）；纯文本仍空白则返回空串。
     *
     * @param element 待转换的 HTML 元素（如文章正文容器）
     * @return Markdown 文本，转换与回退均失败时为空白字符串
     */
    public static String convert(Element element) {
        if (element == null) {
            return "";
        }
        // 在副本上操作，避免污染原 Document
        Element cleaned = element.clone();
        // 移除 CSS / JS / 图标等噪声节点
        cleaned.select("style, script, svg").remove();
        // 移除代码块头部（"代码解读"/"复制代码"）、行号与复制按钮等 UI 装饰
        cleaned.select(".code-block-extension-header, .code-block-extension-lineNumBtnContainer, "
                + ".code-block-extension-copyCodeBtn, .copy-code-btn").remove();

        String html = cleaned.html();
        try {
            String markdown = COPY_DOWN.convert(html);
            if (markdown != null && !markdown.isBlank()) {
                return markdown;
            }
            log.warn("HtmlToMarkdown produced empty result, fallback to plain text");
        } catch (Exception e) {
            log.warn("HtmlToMarkdown conversion failed, fallback to plain text", e);
        }
        // 回退：纯文本
        return cleaned.text();
    }
}
