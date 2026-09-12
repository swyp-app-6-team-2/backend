package com.star_pick.starpick.domain.ingestion.infrastructure.gemini;

import com.star_pick.starpick.domain.ingestion.config.IngestionProperties;
import com.star_pick.starpick.domain.ingestion.service.RecipeAnalyzer;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * 분석 Adapter Bean. {@code GcsConfig} 와 같은 구조이고 같은 이유다 — 테스트가 API 키 없이
 * 통과해야 하므로({@code CLAUDE.md} 검증 원칙) 그때는 {@code RecipeAnalyzer} 자리에 가짜 구현을
 * 끼운다.
 *
 * <p><b>{@code ingestion.external.enabled=false} 는 사실상 테스트 전용 스위치다.</b> 이 설정이
 * 이 저장소에서 유일한 {@code RecipeAnalyzer} 생산자라, 대역을 등록하지 않고 끄면
 * {@code IngestionJobProcessor} 가 주입 대상을 찾지 못해 애플리케이션이 기동하지 않는다.
 * {@code gcs.enabled=false} 가 {@code UploadService} 에 대해 갖는 성질과 정확히 같다.
 * "Gemini 를 부르지 않는 운영 모드"가 필요하면 no-op Adapter를 별도로 만들어야 한다.
 */
@Configuration
@ConditionalOnProperty(prefix = "ingestion.external", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class GeminiConfig {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    @Bean
    RecipeAnalyzer recipeAnalyzer(RestClient.Builder restClientBuilder,
                                  JsonMapper jsonMapper,
                                  IngestionProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        return new GeminiRecipeAnalyzer(restClientBuilder, httpClient, jsonMapper, properties.gemini());
    }
}
