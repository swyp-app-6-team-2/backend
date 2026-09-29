package com.star_pick.starpick.global.config;

import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springdoc.core.customizers.OpenApiCustomizer;
import java.util.List;

@Configuration
public class SwaggerConfig {

    private static final String BEARER_SCHEME_NAME = "bearerAuth";

    /** 일반 가입은 무인증, 게스트 전환 가입은 Bearer 인증을 사용한다. */
    @Bean
    public OpenApiCustomizer optionalGuestSignupSecurity() {
        return api -> {
            var path = api.getPaths().get("/api/v1/auth/signup");
            if (path != null && path.getPost() != null) {
                path.getPost().setSecurity(List.of(
                        new SecurityRequirement().addList(BEARER_SCHEME_NAME), new SecurityRequirement()));
            }
        };
    }

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("별따먹자 API")
                        .description("별따먹자 서비스 API 명세서")
                        .version("v0.0.1"))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME_NAME))
                .components(new Components()
                        .addSecuritySchemes(BEARER_SCHEME_NAME, new SecurityScheme()
                                .name(BEARER_SCHEME_NAME)
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")));

    }
}
