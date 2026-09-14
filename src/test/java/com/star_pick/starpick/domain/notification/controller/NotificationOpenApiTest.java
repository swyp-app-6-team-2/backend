package com.star_pick.starpick.domain.notification.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.star_pick.starpick.domain.notification.domain.PushPlatform;
import com.star_pick.starpick.domain.notification.service.PushTokenService;
import com.star_pick.starpick.global.security.jwt.JwtProvider;
import com.star_pick.starpick.support.IntegrationTest;
import com.star_pick.starpick.support.TestFixtures;
import java.sql.Timestamp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
class NotificationOpenApiTest {

    private static final Long USER_A = 1L;
    private static final Long USER_B = 2L;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtProvider jwtProvider;
    @Autowired
    private PushTokenService tokenService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private TestFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures.reset();
        fixtures.seedUser(USER_A);
        fixtures.seedUser(USER_B);
        tokenService.register(USER_A, "phone", PushPlatform.IOS);
    }

    @Test
    @DisplayName("첫 호출에 opened_at 을 기록하고 다시 불러도 첫 시각을 유지한다")
    void recordsFirstOpenOnly() throws Exception {
        long id = log(USER_A, "SENT", "2026-09-14T03:00:00Z");

        open(USER_A, id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("알림 오픈이 기록되었습니다."))
                .andExpect(jsonPath("$.data").doesNotExist());
        Timestamp first = openedAt(id);
        assertThat(first).isNotNull();

        open(USER_A, id).andExpect(status().isOk());
        assertThat(openedAt(id)).isEqualTo(first);
    }

    @Test
    @DisplayName("발송 중에 멈춘 PROCESSING 알림도 기록한다")
    void recordsProcessing() throws Exception {
        long id = log(USER_A, "PROCESSING", "2026-09-14T03:00:00Z");

        open(USER_A, id).andExpect(status().isOk());
        assertThat(openedAt(id)).isNotNull();
    }

    @Test
    @DisplayName("남의 알림과 없는 알림은 같은 404")
    void notFound() throws Exception {
        long id = log(USER_A, "SENT", "2026-09-14T03:00:00Z");

        open(USER_B, id)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data.code").value("NOTIFICATION_NOT_FOUND"));
        open(USER_A, id + 1000)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data.code").value("NOTIFICATION_NOT_FOUND"));
        assertThat(openedAt(id)).isNull();
    }

    @Test
    @DisplayName("토큰이 다른 계정으로 넘어가도 알림 주인은 이전 사용자다")
    void ownershipSurvivesTokenReassignment() throws Exception {
        long id = log(USER_A, "SENT", "2026-09-14T03:00:00Z");
        tokenService.register(USER_B, "phone", PushPlatform.IOS);

        open(USER_B, id).andExpect(status().isNotFound());
        open(USER_A, id).andExpect(status().isOk());
    }

    @Test
    @DisplayName("id 가 숫자가 아니면 INVALID_REQUEST_FORMAT")
    void nonNumericId() throws Exception {
        mockMvc.perform(post("/api/v1/notifications/abc/open")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtProvider.generateTokens(USER_A).accessToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.code").value("INVALID_REQUEST_FORMAT"));
    }

    private long log(Long userId, String status, String scheduledAt) {
        return jdbcTemplate.queryForObject("""
                insert into push_log (user_id, push_token_id, scheduled_at, status)
                values (?, (select id from push_token where token = 'phone'), cast(? as timestamptz), ?)
                returning id
                """, Long.class, userId, scheduledAt, status);
    }

    private Timestamp openedAt(long id) {
        return jdbcTemplate.queryForObject("select opened_at from push_log where id = ?", Timestamp.class, id);
    }

    private ResultActions open(Long userId, long id) throws Exception {
        return mockMvc.perform(post("/api/v1/notifications/{id}/open", id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtProvider.generateTokens(userId).accessToken()));
    }
}
