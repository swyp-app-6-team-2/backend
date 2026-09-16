package com.star_pick.starpick.global.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 주입 가능한 {@link Clock}. 시각을 직접 만드는 대신 이 Bean 을 주입받으면 테스트가 고정된
 * {@code Clock}으로 교체해 자정·마감 시각 같은 경계를 재현할 수 있다.
 *
 * <p>UTC 를 쓴다. KST 등 시간대 변환은 각 호출부가 {@code ZoneId.of("Asia/Seoul")}로 한다
 * (기존 {@code IngestionJobService}·{@code InquiryService} 와 같은 방식).
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
