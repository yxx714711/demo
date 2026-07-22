package com.waic.springaidemo.ai.config;

import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动期校验：把自动装配的 OllamaChatModel 解析出的默认参数打印到日志，
 * 用于确认 application.yml 中的 spring.ai.ollama.chat.* 真正接管了推理参数
 * （model / num-ctx / temperature / think），而不是被代码硬编码覆盖。
 */
@Slf4j
@Component
@NullMarked
public class LlmOptionsLogger implements ApplicationRunner {

    private final OllamaChatModel chatModel;

    public LlmOptionsLogger(OllamaChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("[llm-options] resolved defaults = {}", chatModel.getOptions());
    }
}
