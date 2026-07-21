package com.waic.springaidemo.common.exception;

import java.io.IOException;

/**
 * 正文不存在（HTTP 404 / 文章节点缺失 / 正文为空）。
 * <p>语义为「终态、无正文」，由 pipeline 捕获后标记 {@code HotItem.CONTENT_NOT_FOUND}（"404"），不再重试。
 * 继承 {@link Exception}（受检）以区别于瞬时 {@link IOException}，避免被统一重试逻辑误吞。</p>
 */
public class ContentNotFoundException extends Exception {

    public ContentNotFoundException(String message) {
        super(message);
    }

    public ContentNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
