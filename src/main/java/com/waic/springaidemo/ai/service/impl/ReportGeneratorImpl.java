package com.waic.springaidemo.ai.service.impl;

import com.waic.springaidemo.ai.service.ReportGenerator;
import com.waic.springaidemo.ai.entity.SummaryContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClient.PromptUserSpec;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 报告生成器实现：只调 LLM 生成节点总结文本，不落盘
 */
@Slf4j
@Service
public class ReportGeneratorImpl implements ReportGenerator {

    private static final ClassPathResource ITEM_TEMPLATE = new ClassPathResource("prompts/item-summary.st");
    private static final ClassPathResource LEAF_TEMPLATE = new ClassPathResource("prompts/leaf-summary.st");
    private static final ClassPathResource NODE_TEMPLATE = new ClassPathResource("prompts/node-summary.st");
    private static final String SYSTEM_PROMPT = """
            你是一位资深技术趋势分析师，长期跟踪全球开源生态与前沿技术动态，擅长从大量技术文章、项目文档与社区讨论中提炼共性主题、识别关键趋势与高价值信号。
            你的总结风格专业、克制、信息密度高，注重归纳升华而非简单罗列，能够为技术从业者提供清晰的判断依据与阅读价值。
            
            严格要求：
            1. 仅基于所提供的材料进行总结，不得编造材料中未出现的技术、项目、数据或结论；材料缺失时方可基于标题合理推断，并明确标注为推断。
            2. 聚焦技术实质与工程价值，剔除营销话术、空洞口号与重复表述；区分"事实/已有进展"与"趋势判断"，趋势判断需给出简短依据。
            3. 输出以中文为主，仅保留必要的英文专有名词、命令、库名或代码标识，不做全文翻译。
            4. 用标准 Markdown 格式输出，标题、列表、代码块请正确使用对应标记；层次清晰、重点突出。
            5. 不要添加任何额外解释、前后缀或问候语，直接输出总结正文。""";

    private static final String PING_SYSTEM =
            "你是用于连通性测试的助手，请用一句话简短回复，确认服务可用即可。/no_think";

    private final ChatClient chatClient;

    public ReportGeneratorImpl(ChatModel chatModel) {
        this.chatClient = ChatClient.builder(chatModel)
                .defaultOptions(OllamaChatOptions.builder()
                        .temperature(0.5)
                        .disableThinking()
                        .numCtx(16384))
                .defaultSystem("""
                        严禁使用 thinking 模式
                        你是简洁高效的助手，直接给出答案，不要任何思考过程、标签、推理步骤。/no_think
                        """)
                .build();
    }

    @Override
    public String summarizeItem(SummaryContext ctx, String input) {
        return callLlm(ITEM_TEMPLATE, ctx, input);
    }

    @Override
    public String summarizeLeaf(SummaryContext ctx, String input) {
        return callLlm(LEAF_TEMPLATE, ctx, input);
    }

    @Override
    public String summarizeNode(SummaryContext ctx, String input) {
        return callLlm(NODE_TEMPLATE, ctx, input);
    }

    private String callLlm(ClassPathResource template, SummaryContext ctx, String input) {
        log.info("[stream-start] level={} maxChars={} inputLen={} template={}",
                ctx.getLevel(), ctx.getMaxChars(), input.length(),
                template.getFilename());
        return streamAndAggregate("level=" + ctx.getLevel(),
                SYSTEM_PROMPT,
                u -> u.text(template)
                        .param("maxChars", ctx.getMaxChars())
                        .param("input", input));
    }

    @Override
    public String ping(String prompt) {
        log.info("[stream-start] stage=ping promptLen={}", prompt.length());
        return streamAndAggregate("ping", PING_SYSTEM, u -> u.text(prompt));
    }

    /**
     * 流式聚合：逐 chunk 接收并累加到 StringBuilder，返回完整文本。
     * 中途失败则 .block() 抛出异常（与 .call() 行为一致）。
     * 日志：INFO 开始/结束（首次 token 延迟、chunk 数、总字符、耗时），DEBUG 每 chunk。
     */
    private String streamAndAggregate(String stage, String systemPrompt,
                                      Consumer<PromptUserSpec> userSpec) {
        long startMs = System.currentTimeMillis();
        Instant start = Instant.now();
        StringBuilder sb = new StringBuilder();
        AtomicInteger chunkCount = new AtomicInteger(0);
        AtomicLong firstTokenMs = new AtomicLong(-1);
        chatClient.prompt()
                .system(systemPrompt)
                .user(userSpec)
                .stream()
                .content()
                .doOnNext(chunk -> {
                    int seq = chunkCount.incrementAndGet();
                    if (seq == 1) {
                        firstTokenMs.set(Duration.between(start, Instant.now()).toMillis());
                    }
                    sb.append(chunk);
                    log.info("[stream] stage={} seq={} chunkLen={} cumulative={} chunk={}",
                            stage, seq, chunk.length(), sb.length(), chunk);
                })
                .then()
                .block();
        long elapsedMs = System.currentTimeMillis() - startMs;
        log.info("[stream-end] stage={} chunks={} chars={} firstTokenMs={} elapsedMs={}",
                stage, chunkCount.get(), sb.length(), firstTokenMs.get(), elapsedMs);
        return sb.toString();
    }
}
