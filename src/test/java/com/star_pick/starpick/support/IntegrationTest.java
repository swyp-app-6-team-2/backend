package com.star_pick.starpick.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * 전체 Spring Context + Testcontainers PostgreSQL 을 사용하는 통합 테스트.
 *
 * <p>이 애노테이션을 붙이지 않은 {@code @SpringBootTest} 는 DataSource 설정이 없어
 * 기동 단계에서 실패한다. 그것이 의도된 동작이다.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@SpringBootTest
@Import(TestcontainersConfiguration.class)
public @interface IntegrationTest {
}
