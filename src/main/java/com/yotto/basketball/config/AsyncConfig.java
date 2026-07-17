package com.yotto.basketball.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "scrapeExecutor")
    public Executor scrapeExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(5);
        executor.setThreadNamePrefix("scrape-");
        executor.initialize();
        return executor;
    }

    // Dedicated pool for account emails — must never queue behind (or reject
    // because of) long-running scrapes. Generous queue: a stuck SMTP retry
    // holds a thread for 30s.
    @Bean(name = "mailExecutor")
    public Executor mailExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("mail-");
        executor.initialize();
        return executor;
    }

    // Dedicated single-thread pool for admin broadcast sends. Kept off mailExecutor
    // so a bulk fan-out can never starve or reject latency-sensitive transactional
    // account mail (verify/reset). One thread paces delivery gently for Mailgun; each
    // broadcast is one task that loops its own recipients internally.
    @Bean(name = "broadcastExecutor")
    public Executor broadcastExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(10);
        executor.setThreadNamePrefix("broadcast-");
        executor.initialize();
        return executor;
    }
}
