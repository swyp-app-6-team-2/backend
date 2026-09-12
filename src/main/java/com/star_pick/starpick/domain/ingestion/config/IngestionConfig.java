package com.star_pick.starpick.domain.ingestion.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Ingestion 설정값 활성화.
 *
 * <p>실행 스레드 풀은 여기서 빈으로 만들지 않는다. 이유는
 * {@code IngestionWorker#ingestionExecutor} 에 있다.
 */
@Configuration
@EnableConfigurationProperties(IngestionProperties.class)
public class IngestionConfig {
}
