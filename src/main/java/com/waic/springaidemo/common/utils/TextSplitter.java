package com.waic.springaidemo.common.utils;

import java.util.ArrayList;
import java.util.List;

/**
 * 纯函数文本切片工具：在尽量不拆断整句的前提下，把长文切成若干块，
 * 每块长度不超过 maxChars，相邻块之间保留 overlapRatio 比例的重叠。
 * 不依赖 LLM、无状态、可单测；切片结果确定（同样输入 → 同样的块序列）。
 * <p>落刀优先级：段落分隔（\n\n）> 单换行 > 句末标点（. 。 ! ? ！ ？）。
 * 仅当单句长度超过 maxChars 才退化为硬切（最大程度避免拆断整句）。</p>
 */
public final class TextSplitter {

    private static final String SENTENCE_ENDS = ".。!?！？";

    private TextSplitter() {
    }

    /**
     * 将文本切成块。
     *
     * @param text          待切分文本（可为 null/blank，返回空列表）
     * @param maxChars      单块最大字符数
     * @param overlapRatio  相邻块重叠比例（0~0.5），实际重叠 = round(maxChars * ratio)
     * @return 切片列表；文本本身不超过 maxChars 时返回仅含整篇的单元素列表
     */
    public static List<String> split(String text, int maxChars, double overlapRatio) {
        if (text == null || text.isBlank()) {
            return new ArrayList<>();
        }
        String t = text.trim();
        if (maxChars <= 0) {
            return List.of(t);
        }
        if (t.length() <= maxChars) {
            return List.of(t);
        }
        int overlap = clamp((int) Math.round(maxChars * overlapRatio), 0, maxChars / 2);

        List<String> chunks = new ArrayList<>();
        int start = 0;
        int n = t.length();
        while (start < n) {
            int rawEnd = Math.min(start + maxChars, n);
            int end = rawEnd;
            if (rawEnd < n) {
                int cut = findCut(t, start, rawEnd);
                if (cut > start) {
                    end = cut; // 在自然段/句末边界落刀
                }
                // cut <= start 表示本段无自然边界（单句超长），硬切到 rawEnd
            }
            String chunk = t.substring(start, end).trim();
            if (!chunk.isEmpty()) {
                chunks.add(chunk);
            }
            if (end >= n) {
                break;
            }
            // 下一块起点 = 本块终点 - 重叠，并向后对齐到自然边界（避免从句子中间开始）
            int next = end - overlap;
            if (next <= start) {
                next = end; // 安全检查，保证推进
            }
            next = alignForward(t, next);
            start = Math.max(next, start + 1); // 保证严格推进，杜绝死循环
        }
        return chunks;
    }

    /**
     * 在 (start, rawEnd] 内寻找最靠后的自然落刀点（段落/换行/句末），返回切分位置（含边界字符之后）。
     * 找不到返回 -1（调用方退化为硬切 rawEnd）。
     */
    private static int findCut(String t, int start, int rawEnd) {
        int p = t.lastIndexOf("\n\n", rawEnd - 1);
        if (p > start && p + 2 <= rawEnd) {
            return p + 2; // 段落分隔符之后
        }
        int nl = t.lastIndexOf('\n', rawEnd - 1);
        if (nl > start) {
            return nl + 1; // 单换行之后
        }
        for (int i = rawEnd - 1; i > start; i--) {
            if (isSentenceEnd(t.charAt(i))) {
                return i + 1; // 句末标点之后
            }
        }
        return -1;
    }

    /**
     * 从 pos 起向后找到最近的自然边界（换行/句末），返回该边界之后作为起点；无则原样返回。
     */
    private static int alignForward(String t, int pos) {
        if (pos >= t.length()) {
            return pos;
        }
        for (int i = pos; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '\n' || isSentenceEnd(c)) {
                return i + 1;
            }
        }
        return pos;
    }

    private static boolean isSentenceEnd(char c) {
        return SENTENCE_ENDS.indexOf(c) >= 0;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.min(hi, Math.max(lo, v));
    }
}
