package com.star_pick.starpick.domain.notification.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.star_pick.starpick.global.security.jwt.JwtProvider;
import com.star_pick.starpick.support.IntegrationTest;
import com.star_pick.starpick.support.TestFixtures;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@IntegrationTest
class PushTokenApiTest {

    private static final Long OWNER_ID = 1L;
    private static final Long OTHER_ID = 2L;
    private static final String URL = "/api/v1/push-tokens";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtProvider jwtProvider;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private TestFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures.reset();
        fixtures.seedUser(OWNER_ID);
        fixtures.seedUser(OTHER_ID);
    }

    @Test
    @DisplayName("새 토큰을 활성으로 저장하고 재등록은 platform 을 갱신한다")
    void registerAndReRegister() throws Exception {
        send(put(URL), OWNER_ID, "{\"token\":\"tok-1\",\"platform\":\"IOS\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("푸시 토큰이 저장되었습니다."));
        send(put(URL), OWNER_ID, "{\"token\":\"tok-1\",\"platform\":\"ANDROID\"}").andExpect(status().isOk());

        assertThat(row("tok-1")).containsEntry("user_id", OWNER_ID)
                .containsEntry("platform", "ANDROID").containsEntry("active", true);
        assertThat(count()).isOne();
    }

    @Test
    @DisplayName("다른 계정이 같은 토큰을 등록하면 그 계정으로 넘어가고 다시 활성화된다")
    void reassignToOtherUser() throws Exception {
        send(put(URL), OWNER_ID, "{\"token\":\"tok-1\",\"platform\":\"IOS\"}");
        send(delete(URL), OWNER_ID, "{\"token\":\"tok-1\"}");
        send(put(URL), OTHER_ID, "{\"token\":\"tok-1\",\"platform\":\"IOS\"}").andExpect(status().isOk());

        assertThat(row("tok-1")).containsEntry("user_id", OTHER_ID).containsEntry("active", true);
        assertThat(count()).isOne();
    }

    @Test
    @DisplayName("해제는 내 토큰만 비활성화하고, 남의 토큰·없는 토큰에도 200 을 준다")
    void unregister() throws Exception {
        send(put(URL), OWNER_ID, "{\"token\":\"mine\",\"platform\":\"IOS\"}");
        send(put(URL), OTHER_ID, "{\"token\":\"theirs\",\"platform\":\"IOS\"}");

        send(delete(URL), OWNER_ID, "{\"token\":\"mine\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("푸시 토큰이 해제되었습니다."));
        send(delete(URL), OWNER_ID, "{\"token\":\"theirs\"}").andExpect(status().isOk());
        send(delete(URL), OWNER_ID, "{\"token\":\"missing\"}").andExpect(status().isOk());

        assertThat(row("mine")).containsEntry("active", false);
        assertThat(row("theirs")).containsEntry("active", true);
    }

    @Test
    @DisplayName("요청 검증")
    void validation() throws Exception {
        String tooLong = "a".repeat(513);
        for (String body : List.of("{}", "{\"token\":\"\",\"platform\":\"IOS\"}", "{\"token\":\"t\"}",
                "{\"token\":\"%s\",\"platform\":\"IOS\"}".formatted(tooLong))) {
            send(put(URL), OWNER_ID, body)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.data.code").value("REQUEST_VALIDATION_FAILED"));
        }
        send(put(URL), OWNER_ID, "{\"token\":\"t\",\"platform\":\"WEB\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.code").value("INVALID_REQUEST_FORMAT"));
        send(delete(URL), OWNER_ID, "{\"token\":\"\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.code").value("REQUEST_VALIDATION_FAILED"));
        assertThat(count()).isZero();
    }

    @Test
    @DisplayName("같은 새 토큰을 동시에 두 번 등록해도 500 없이 row 하나")
    void concurrentRegister() throws Exception {
        assertThat(concurrently(() -> send(put(URL), OWNER_ID, "{\"token\":\"same\",\"platform\":\"IOS\"}")
                .andReturn().getResponse().getStatus())).containsExactly(200, 200);
        assertThat(count()).isOne();
    }

    /** 두 요청을 같은 순간에 출발시킨다({@code NotificationSettingApiTest#concurrently} 와 같다). */
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

    private ResultActions send(MockHttpServletRequestBuilder request, Long userId, String body) throws Exception {
        return mockMvc.perform(request
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtProvider.generateTokens(userId).accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private Map<String, Object> row(String token) {
        return jdbcTemplate.queryForMap("select user_id, platform, active from push_token where token = ?", token);
    }

    private int count() {
        return jdbcTemplate.queryForObject("select count(*) from push_token", Integer.class);
    }
}
