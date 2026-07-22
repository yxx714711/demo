package com.waic.springaidemo.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 有界线程池配置：供 Pipeline 组合任务（抓取+汇总）异步执行。
 * 不同 period 可并发跑，但队列有界，避免日报任务堆积打爆 LLM。
 * 拒绝策略用 CallerRunsPolicy：队列满时由调用线程（HTTP 线程）同步执行，
 * 保证任务不丢失（最坏情况触发接口在饱和时才同步等完，仍会落 SUCCESS/FAILED）。
 */
@Configuration
public class ExecutorConfig {

    @Bean(name = "pipelineTaskExecutor")
    public Executor pipelineTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(16);
        executor.setThreadNamePrefix("pipeline-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }
}
