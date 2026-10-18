package com.multiagent.discuss.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;

@Configuration
public class AsyncConfig {

    @Bean(name = "thinkExecutor")
    public Executor thinkExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(12);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("think-");
        executor.initialize();
        return executor;
    }

    @Bean(name = "visionExecutor")
    public Executor visionExecutor(AppProperties props) {
        int n = Math.max(1, props.getSession().getVisionPageConcurrency());
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(n);
        executor.setMaxPoolSize(n);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("vision-");
        executor.initialize();
        return executor;
    }

    @Bean
    public Semaphore visionPageSemaphore(AppProperties props) {
        return new Semaphore(Math.max(1, props.getSession().getVisionPageConcurrency()));
    }
}
