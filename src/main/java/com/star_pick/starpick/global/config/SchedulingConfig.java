package com.star_pick.starpick.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 스케줄링은 조건 없이 켠다. 도메인별로 조건부로 켜면 안 된다 — 한 번 켜지면 컨텍스트의 모든
 * {@code @Scheduled} 가 돌아, 다른 도메인의 작업까지 실행된다. 실행 여부는 각 도메인의
 * {@code *Schedule} Bean 이 자기 조건으로 등록될지로 정한다.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
