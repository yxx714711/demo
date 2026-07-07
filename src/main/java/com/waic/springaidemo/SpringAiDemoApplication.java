package com.waic.springaidemo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring AI Demo 启动类，启用定时任务支持以驱动每日聚合调度。
 *
 * @author 10542
 */
@SpringBootApplication
@EnableScheduling
public class SpringAiDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(SpringAiDemoApplication.class, args);
    }
}
