package com.waic.springaidemo.ai.service.impl;

import com.waic.springaidemo.ai.service.ReportGenerator;
import com.waic.springaidemo.common.entity.FetchResult;
import com.waic.springaidemo.common.entity.HotItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 报告生成器实现
 */
@Slf4j
@Service
public class ReportGeneratorImpl implements ReportGenerator {

    private static final String PROMPT_TEMPLATE = "prompts/daily-report.st";
    private static final int MAX_ITEMS_PER_SOURCE = 5;
    private static final int MAX_CONTENT_LENGTH = 3000;

    private final ChatClient chatClient;

    public ReportGeneratorImpl(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    @Override
    public String generateDailyReport(LocalDate date, List<FetchResult> results) {
        String prompt = buildPrompt(date, results);
        log.info("Generating daily report for {} with prompt length {}", date, prompt.length());
        return chatClient.prompt()
                .user(prompt)
                .call()
                .content();
    }

    private String buildPrompt(LocalDate date, List<FetchResult> results) {
        String template = loadPromptTemplate();
        String dateStr = date.format(DateTimeFormatter.ISO_LOCAL_DATE);
        String dataSection = buildDataSection(results);
        return template.replace("{{date}}", dateStr)
                .replace("{{data}}", dataSection);
    }

    private String loadPromptTemplate() {
        try {
            ClassPathResource resource = new ClassPathResource(PROMPT_TEMPLATE);
            return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Failed to load prompt template, using default prompt", e);
            return """
                    你是一位技术趋势分析师。请根据以下 {{date}} 抓取的热门项目/文章数据，生成一份技术日报。
                    报告使用 Markdown 格式，包含以下内容：
                    1. 今日热门总览
                    2. 各平台亮点
                    3. 跨平台趋势观察
                    4. 值得关注的新项目/文章
                    
                    数据如下：
                    {{data}}
                    """;
        }
    }

    private String buildDataSection(List<FetchResult> results) {
        StringBuilder builder = new StringBuilder();
        for (FetchResult result : results) {
            if (result.getItems().isEmpty()) {
                continue;
            }
            builder.append("\n## 数据源：").append(result.getSource().getName())
                    .append(" / 周期：").append(result.getPeriod().getName())
                    .append(" / 分类：").append(result.getCategory())
                    .append(" / 语言：").append(result.getLanguage()).append("\n");

            List<HotItem> selectedItems = result.getItems().stream()
                    .limit(MAX_ITEMS_PER_SOURCE)
                    .collect(Collectors.toList());

            for (HotItem item : selectedItems) {
                builder.append("### ").append(item.getTitle()).append("\n");
                builder.append("- 链接：").append(item.getUrl()).append("\n");
                builder.append("- 摘要：").append(item.getSummary()).append("\n");
                String content = loadContent(item.getContentPath());
                if (!content.isBlank()) {
                    builder.append("- 内容节选：\n").append(truncate(content, MAX_CONTENT_LENGTH)).append("\n");
                }
                builder.append("\n");
            }
        }
        return builder.toString();
    }

    private String loadContent(String contentPath) {
        if (contentPath == null || contentPath.isBlank()) {
            return "";
        }
        try {
            return Files.readString(Paths.get(contentPath), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Failed to load content from {}", contentPath);
            return "";
        }
    }

    private String truncate(String content, int maxLength) {
        if (content.length() <= maxLength) {
            return content;
        }
        return content.substring(0, maxLength) + "\n\n...[内容已截断]";
    }
}
