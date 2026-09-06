package com.star_pick.starpick.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 테스트용 PostgreSQL 컨테이너.
 *
 * <p>이미지 태그는 docker-compose.yml 및 운영 목표 버전(PostgreSQL 18)과 맞춘다.
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
}
