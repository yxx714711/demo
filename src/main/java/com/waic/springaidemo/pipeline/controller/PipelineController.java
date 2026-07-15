package com.waic.springaidemo.pipeline.controller;

import com.waic.springaidemo.ai.service.ReportGenerator;
import com.waic.springaidemo.common.entity.FetchResult;
import com.waic.springaidemo.common.entity.ReportResult;
import com.waic.springaidemo.common.enums.DataSourceEnum;
import com.waic.springaidemo.common.enums.PeriodEnum;
import com.waic.springaidemo.pipeline.service.PipelineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Pipeline 控制器
 */
@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class PipelineController {

    private final PipelineService pipelineService;
    private final ReportGenerator reportGenerator;

    @Value("${spring.ai.ollama.chat.model:unknown}")
    private String ollamaModel;

    /**
     * 手动触发抓取
     *
     * @param period 周期，可选 daily/weekly/monthly
     * @param date   日期，默认今天
     * @return 抓取结果
     * @throws IOException IO 异常
     */
    @PostMapping("/pipeline/crawl/{period}")
    public List<FetchResult> crawl(@PathVariable String period,
                                   @RequestParam(required = false)
                                   @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) throws IOException {
        if (date == null) {
            date = LocalDate.now();
        }
        return pipelineService.runCrawl(date, PeriodEnum.of(period));
    }

    /**
     * 手动触发指定数据源的抓取
     *
     * @param source 数据源（github/gitee/juejin）
     * @param period 周期，必填 daily/weekly/monthly
     * @param date   日期，默认今天
     * @return 抓取结果
     * @throws IOException IO 异常
     */
    @PostMapping("/pipeline/crawl/source/{source}")
    public List<FetchResult> crawlBySource(@PathVariable String source,
                                           @RequestParam String period,
                                           @RequestParam(required = false)
                                           @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) throws IOException {
        if (date == null) {
            date = LocalDate.now();
        }
        return pipelineService.runCrawlBySource(DataSourceEnum.of(source), date, PeriodEnum.of(period));
    }

    /**
     * 组合任务触发：抓取 + 汇总生成日报（异步）。
     * <p>立即返回，不阻塞等待：任务进行中（RUNNING）返回 409 阻止重复触发；
     * 其余状态（首次/SUCCESS 再触发/失败续跑）返回 202 并在后台启动。日期固定为当天。</p>
     *
     * @param period 周期，可选 daily/weekly/monthly，默认 daily
     * @return 202 Accepted 已启动；409 Conflict 任务进行中
     */
    @PostMapping("/pipeline/run")
    public ResponseEntity<Void> runPipeline(@RequestParam(required = false, defaultValue = "daily") String period) {
        PipelineService.PipelineTriggerStatus status = pipelineService.triggerPipeline(PeriodEnum.of(period));
        if (status == PipelineService.PipelineTriggerStatus.REJECTED_RUNNING) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }

    /**
     * 手动触发每日报告生成
     *
     * @param date 日期，默认今天
     * @return 报告结果（结构化）
     * @throws IOException IO 异常
     */
    @PostMapping("/report/daily")
    public ReportResult generateDailyReport(@RequestParam(required = false)
                                        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) throws IOException {
        if (date == null) {
            date = LocalDate.now();
        }
        long start = System.currentTimeMillis();
        log.info("Daily report start date={}", date);
        try {
            ReportResult result = pipelineService.generateDailyReport(date);
            long elapsed = System.currentTimeMillis() - start;
            log.info("Daily report success date={} elapsedMs={} sourceCount={} categoryCount={} path={} summaryLen={}",
                    date, elapsed, result.getSourceCount(), result.getCategoryCount(),
                    result.getPath(), safeLen(result.getSummary()));
            return result;
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            log.error("Daily report failed date={} elapsedMs={}", date, elapsed, e);
            throw e;
        }
    }

    /**
     * 测试 chatClient（Ollama）可用性：默认 prompt="你好"，走流式路径。
     */
    @PostMapping("/llm/ping")
    public Map<String, Object> pingLlm(@RequestParam(required = false, defaultValue = "你好") String prompt) {
        long start = System.currentTimeMillis();
        log.info("LLM ping start prompt={}", prompt);
        try {
            String reply = reportGenerator.ping(prompt);
            long elapsed = System.currentTimeMillis() - start;
            log.info("LLM ping success elapsedMs={} replyLen={}", elapsed, safeLen(reply));
            return Map.of(
                    "ok", true,
                    "latencyMs", elapsed,
                    "prompt", prompt,
                    "reply", truncate(reply, 200),
                    "model", ollamaModel);
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            log.error("LLM ping failed prompt={} elapsedMs={}", prompt, elapsed, e);
            throw e;
        }
    }

    private static int safeLen(String s) {
        return s == null ? 0 : s.length();
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
