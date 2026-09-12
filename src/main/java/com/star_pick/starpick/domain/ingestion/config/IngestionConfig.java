package com.star_pick.starpick.domain.ingestion.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableConfigurationProperties(IngestionProperties.class)
public class IngestionConfig {

    @Bean
    ThreadPoolTaskExecutor ingestionExecutor(IngestionProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.worker().concurrency());
        executor.setMaxPoolSize(properties.worker().concurrency());
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("ingestion-");
        return executor;
    }
}
