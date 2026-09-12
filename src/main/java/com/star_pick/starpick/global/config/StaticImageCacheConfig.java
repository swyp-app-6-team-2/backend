package com.star_pick.starpick.global.config;

import java.time.Duration;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 재료 아이콘 같은 공개 정적 이미지의 캐시 정책.
 *
 * <p>전역 {@code spring.web.resources.cache} 대신 이 경로에만 건다. 전역으로 걸면 dev 콘솔 HTML 까지
 * 캐시되고, Security 가 나머지 정적 리소스에 붙이는 {@code no-store} 도 함께 사라진다.
 */
@Configuration
public class StaticImageCacheConfig implements WebMvcConfigurer {

    private static final Duration ICON_CACHE_DURATION = Duration.ofDays(7);

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/images/**")
                .addResourceLocations("classpath:/static/images/")
                .setCacheControl(CacheControl.maxAge(ICON_CACHE_DURATION).cachePublic());
    }
}
