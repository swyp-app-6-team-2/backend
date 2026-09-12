package com.star_pick.starpick.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 테스트용 인프라 대역.
 *
 * <p><b>여기에는 Service 계층에 의존하는 Bean 을 두지 않는다.</b> CLAUDE.md §11 이 안내하는
 * {@code @DataJpaTest + @Import(TestcontainersConfiguration.class)} 조합에서는 {@code @Service} 가
 * 걸러져 Context 가 기동조차 못 한다(실측). 픽스처 {@code TestFixtures} 는 {@code @IntegrationTest} 가 따로 import 한다.
 *
 * <p>PostgreSQL 이미지 태그는 docker-compose.yml 및 운영 목표 버전(PostgreSQL 18)과 맞춘다.
 * {@code @ServiceConnection} 이 만들어 주는 JdbcConnectionDetails bean 은
 * spring.datasource.* property 보다 우선하므로 접속 정보를 따로 지정하지 않는다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:18");

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(POSTGRES_IMAGE);
    }

    /**
     * test resources 의 {@code gcs.enabled=false} 가 실제 클라이언트 Bean 을 꺼두므로
     * 이 Bean 이 {@code ObjectStorage} 자리를 채운다. 덕분에 테스트에 GCP 자격증명이 필요 없다.
     * 구체 타입으로 선언해 테스트가 {@code putObject} 같은 조작 메서드를 주입받아 쓸 수 있게 한다.
     */
    @Bean
    FakeObjectStorage objectStorage() {
        return new FakeObjectStorage();
    }

    @Bean
    FakeRecipeAnalyzer recipeAnalyzer() {
        return new FakeRecipeAnalyzer();
    }
}
