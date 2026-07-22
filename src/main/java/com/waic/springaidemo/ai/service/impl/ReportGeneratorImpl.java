package com.waic.springaidemo.ai.service.impl;

import com.waic.springaidemo.ai.components.PromptTemplateManager;
import com.waic.springaidemo.ai.service.ReportGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClient.PromptUserSpec;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 报告生成器实现：纯 LLM 单段调用，不落盘、不感知层级与切片。
 * 长度上限由 PromptTemplateManager 按模板映射得到，不从调用方传入。
 */
@Slf4j
@Service
public class ReportGeneratorImpl implements ReportGenerator {

    private static final Resource SYSTEM_PROMPT = new ClassPathResource("prompts/system-prompt.st");

    private final ChatClient chatClient;
    private final PromptTemplateManager promptTemplateManager;

    public ReportGeneratorImpl(ChatModel chatModel, PromptTemplateManager promptTemplateManager) {
        this.chatClient = ChatClient.builder(chatModel)
                .defaultSystem(SYSTEM_PROMPT)
                .build();
        this.promptTemplateManager = promptTemplateManager;
    }

    @Override
    public String summarize(String input, Resource template) {
        int maxChars = promptTemplateManager.maxChars(template);
        return callLlm(template, maxChars, input);
    }

    private String callLlm(Resource template, int maxChars, String input) {
        log.info("[stream-start] maxChars={} inputLen={} template={}",
                maxChars, input.length(), template.getFilename());
        return streamAndAggregate(u -> u.text(template)
                .param("maxChars", maxChars)
                .param("input", input));
    }

    /**
     * 流式聚合：逐 chunk 接收并累加到 StringBuilder，返回完整文本。
     * 中途失败则 .block() 抛出异常（与 .call() 行为一致）。
     * 日志：INFO 开始/结束（首次 token 延迟、chunk 数、总字符、耗时），DEBUG 每 chunk。
     */
    private String streamAndAggregate(Consumer<PromptUserSpec> userSpec) {
        Instant start = Instant.now();
        StringBuilder sb = new StringBuilder();
        AtomicInteger chunkCount = new AtomicInteger(0);
        AtomicLong firstTokenMs = new AtomicLong(-1);
        chatClient.prompt()
                .user(userSpec)
                .stream()
                .content()
                .doOnNext(chunk -> {
                    int seq = chunkCount.incrementAndGet();
                    if (seq == 1) {
                        firstTokenMs.set(Duration.between(start, Instant.now()).toMillis());
                    }
                    sb.append(chunk);
                })
                .then()
                .block();
        log.info("[stream-end] chunks={} chars={} firstTokenMs={}",
                chunkCount.get(), sb.length(), firstTokenMs.get());
        return sb.toString();
    }
}
