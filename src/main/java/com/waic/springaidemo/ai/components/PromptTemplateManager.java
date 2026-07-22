package com.waic.springaidemo.ai.components;

import com.waic.springaidemo.common.config.SummaryProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/**
 * 提示词模板与长度的单一来源。
 * <p>持有三个用户模板 Resource（leaf / node / report），并按模板映射输出长度上限。
 * ai 与 pipeline 都注入本类：ai 用 {@link #maxChars(Resource)} 取长度，
 * pipeline 用各 getter 取模板实例后传给 {@code ReportGenerator.summarize}。</p>
 * <p>长度上限来自配置（app.summary.max-chars.*），非硬编码。</p>
 */
@Component
public class PromptTemplateManager {

    private final Resource leafTemplate;
    private final Resource nodeTemplate;
    private final Resource reportTemplate;
    private final int leafMaxChars;
    private final int nodeMaxChars;
    private final int reportMaxChars;

    public PromptTemplateManager(@Value("classpath:prompts/leaf-prompt.st") Resource leafTemplate,
                                 @Value("classpath:prompts/node-prompt.st") Resource nodeTemplate,
                                 @Value("classpath:prompts/report-prompt.st") Resource reportTemplate,
                                 SummaryProperties props) {
        this.leafTemplate = leafTemplate;
        this.nodeTemplate = nodeTemplate;
        this.reportTemplate = reportTemplate;
        this.leafMaxChars = props.getMaxChars().getLeaf();
        this.nodeMaxChars = props.getMaxChars().getNode();
        this.reportMaxChars = props.getMaxChars().getReport();
    }

    public Resource leafTemplate() {
        return leafTemplate;
    }

    public Resource nodeTemplate() {
        return nodeTemplate;
    }

    public Resource reportTemplate() {
        return reportTemplate;
    }

    /**
     * 按模板 Resource 取输出长度上限。pipeline 传入的模板即本类 getter 返回的同一实例，
     * 故用身份比较即可；未知模板抛异常以防配置漂移。
     */
    public int maxChars(Resource template) {
        if (template == leafTemplate) {
            return leafMaxChars;
        }
        if (template == nodeTemplate) {
            return nodeMaxChars;
        }
        if (template == reportTemplate) {
            return reportMaxChars;
        }
        throw new IllegalArgumentException(
                "Unknown prompt template: " + (template != null ? template.getFilename() : null));
    }
}
