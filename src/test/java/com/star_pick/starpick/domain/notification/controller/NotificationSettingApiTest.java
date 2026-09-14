package com.star_pick.starpick.domain.notification.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.star_pick.starpick.global.security.jwt.JwtProvider;
import com.star_pick.starpick.support.IntegrationTest;
import com.star_pick.starpick.support.TestFixtures;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
class NotificationSettingApiTest {

    private static final Long OWNER_ID = 1L;
    private static final String URL = "/api/v1/notification-settings";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtProvider jwtProvider;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private TestFixtures fixtures;

    private String accessToken;

    @BeforeEach
    void setUp() {
        fixtures.reset();
        fixtures.seedUser(OWNER_ID);
        accessToken = jwtProvider.generateTokens(OWNER_ID).accessToken();
    }

    @Test
    @DisplayName("설정이 없으면 꺼진 기본값을 주고 row를 만들지 않는다")
    void defaultWhenAbsent() throws Exception {
        mockMvc.perform(get(URL).header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("알림 설정을 조회했습니다."))
                .andExpect(jsonPath("$.data.enabled").value(false))
                .andExpect(jsonPath("$.data.weekdays").isEmpty())
                .andExpect(jsonPath("$.data.timeSlots").isEmpty());

        assertThat(count()).isZero();
    }

    @Test
    @DisplayName("저장하면 정렬해서 보관하고 조회는 그 순서를 준다")
    void saveSortsAndReads() throws Exception {
        save("""
                {"enabled":true,"weekdays":["SUNDAY","MONDAY"],
                 "timeSlots":[{"label":"저녁 알림","time":"18:00"},{"label":"아침 알림","time":"08:00"}]}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("알림 설정이 저장되었습니다."))
                .andExpect(jsonPath("$.data").doesNotExist());

        mockMvc.perform(get(URL).header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.weekdays[0]").value("MONDAY"))
                .andExpect(jsonPath("$.data.weekdays[1]").value("SUNDAY"))
                .andExpect(jsonPath("$.data.timeSlots[0].label").value("아침 알림"))
                .andExpect(jsonPath("$.data.timeSlots[0].time").value("08:00"))
                .andExpect(jsonPath("$.data.timeSlots[1].time").value("18:00"));

        // 발송 조회가 문자열로 비교하므로 DB 에 "HH:mm" 그대로 들어가야 한다.
        assertThat(jdbcTemplate.queryForObject(
                "select time_slots -> 0 ->> 'time' from notification_setting where user_id = ?", String.class, OWNER_ID))
                .isEqualTo("08:00");
        assertThat(jdbcTemplate.queryForObject(
                "select weekdays[1] from notification_setting where user_id = ?", String.class, OWNER_ID))
                .isEqualTo("MONDAY");
    }

    @Test
    @DisplayName("다시 저장하면 전체를 교체한다")
    void overwrites() throws Exception {
        save("""
                {"enabled":true,"weekdays":["MONDAY"],"timeSlots":[{"label":"점심","time":"12:00"}]}
                """);
        save("""
                {"enabled":false,"weekdays":[],"timeSlots":[]}
                """).andExpect(status().isOk());

        mockMvc.perform(get(URL).header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(jsonPath("$.data.enabled").value(false))
                .andExpect(jsonPath("$.data.weekdays").isEmpty())
                .andExpect(jsonPath("$.data.timeSlots").isEmpty());
        assertThat(count()).isOne();
    }

    @Test
    @DisplayName("켜져 있어도 요일·시간대가 비어 있을 수 있다")
    void enabledWithEmptyLists() throws Exception {
        save("""
                {"enabled":true,"weekdays":[],"timeSlots":[]}
                """).andExpect(status().isOk());
    }

    @Test
    @DisplayName("필수값·길이·중복 위반은 REQUEST_VALIDATION_FAILED")
    void validationFailures() throws Exception {
        String longLabel = "가".repeat(256);
        for (String body : List.of(
                "{}",
                "{\"weekdays\":[],\"timeSlots\":[]}",
                "{\"enabled\":true,\"weekdays\":null,\"timeSlots\":[]}",
                "{\"enabled\":true,\"weekdays\":[],\"timeSlots\":null}",
                "{\"enabled\":true,\"weekdays\":[null],\"timeSlots\":[]}",
                "{\"enabled\":true,\"weekdays\":[],\"timeSlots\":[null]}",
                "{\"enabled\":true,\"weekdays\":[],\"timeSlots\":[{\"label\":\" \",\"time\":\"12:00\"}]}",
                "{\"enabled\":true,\"weekdays\":[],\"timeSlots\":[{\"label\":\"%s\",\"time\":\"12:00\"}]}".formatted(longLabel),
                "{\"enabled\":true,\"weekdays\":[],\"timeSlots\":[{\"label\":\"점심\"}]}",
                "{\"enabled\":true,\"weekdays\":[\"MONDAY\",\"MONDAY\"],\"timeSlots\":[]}",
                "{\"enabled\":true,\"weekdays\":[],\"timeSlots\":[{\"label\":\"a\",\"time\":\"12:00\"},{\"label\":\"b\",\"time\":\"12:00\"}]}")) {
            save(body)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.data.code").value("REQUEST_VALIDATION_FAILED"));
        }
        assertThat(count()).isZero();
    }

    @Test
    @DisplayName("없는 요일 값과 HH:mm 이 아닌 시각은 INVALID_REQUEST_FORMAT")
    void formatFailures() throws Exception {
        for (String body : List.of(
                "{\"enabled\":true,\"weekdays\":[\"MON\"],\"timeSlots\":[]}",
                "{\"enabled\":true,\"weekdays\":[],\"timeSlots\":[{\"label\":\"a\",\"time\":\"8:00\"}]}",
                "{\"enabled\":true,\"weekdays\":[],\"timeSlots\":[{\"label\":\"a\",\"time\":\"24:00\"}]}",
                "{\"enabled\":true,\"weekdays\":[],\"timeSlots\":[{\"label\":\"a\",\"time\":\"08:00:00\"}]}")) {
            save(body)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.data.code").value("INVALID_REQUEST_FORMAT"));
        }
    }

    @Test
    @DisplayName("첫 저장이 동시에 두 번 와도 500 없이 row 하나")
    void concurrentFirstSave() throws Exception {
        String body = "{\"enabled\":true,\"weekdays\":[\"MONDAY\"],\"timeSlots\":[]}";
        assertThat(concurrently(() -> save(body).andReturn().getResponse().getStatus())).containsExactly(200, 200);
        assertThat(count()).isOne();
    }

    /** 두 요청을 같은 순간에 출발시킨다. 출발 신호가 없으면 앞 요청이 끝난 뒤 뒤 요청이 돌아도 통과한다. */
    private List<Integer> concurrently(Callable<Integer> call) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("start timeout");
                    }
                    return call.call();
                }));
            }
            try {
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            } finally {
                start.countDown();
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get(20, TimeUnit.SECONDS));
            }
            return statuses;
        }
    }

    private ResultActions save(String body) throws Exception {
        return mockMvc.perform(put(URL)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private int count() {
        return jdbcTemplate.queryForObject("select count(*) from notification_setting", Integer.class);
    }
}
