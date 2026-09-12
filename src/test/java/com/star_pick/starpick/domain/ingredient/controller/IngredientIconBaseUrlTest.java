package com.star_pick.starpick.domain.ingredient.controller;

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.star_pick.starpick.global.security.jwt.JwtProvider;
import com.star_pick.starpick.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** 운영 설정에 끝 슬래시가 하나 이상 들어와도 iconUrl 이 깨지지 않는지 확인한다. */
@IntegrationTest
@TestPropertySource(properties = "starpick.ingredient.icon-base-url=http://localhost:9999//")
class IngredientIconBaseUrlTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProvider jwtProvider;

    @Test
    @DisplayName("base URL 끝 슬래시는 여러 개여도 전부 제거되어 경로가 중복 슬래시로 이어지지 않는다")
    void trimsTrailingSlashFromIconBaseUrl() throws Exception {
        String accessToken = jwtProvider.generateTokens(1L).accessToken();

        mockMvc.perform(get("/api/v1/ingredients")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ingredients[0].iconUrl")
                        .value("http://localhost:9999/images/ingredients/chicken.webp"))
                .andExpect(jsonPath("$.data.ingredients[*].iconUrl",
                        everyItem(not(containsString("//images/")))));
    }
}
