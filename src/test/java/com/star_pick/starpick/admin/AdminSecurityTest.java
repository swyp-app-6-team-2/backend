package com.star_pick.starpick.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.star_pick.starpick.support.IntegrationTest;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.web.servlet.MockMvc;

/** /admin/** 필터 체인이 앱 API 체인과 독립적으로 동작하는지 확인한다. */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class AdminSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    @DisplayName("로그인 없이 관리자 화면에 가면 로그인 화면으로 보낸다")
    void redirectsAnonymousToLogin() throws Exception {
        // Security 7 의 LoginUrlAuthenticationEntryPoint 는 기본으로 상대 URL 을 쓴다.
        mockMvc.perform(get("/admin/inquiries"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/login"));
    }

    @Test
    @DisplayName("로그인 없이 GET /admin 도 관리자 체인이 막아 로그인 화면으로 보낸다")
    void rootRequiresLogin() throws Exception {
        mockMvc.perform(get("/admin"))
                .andExpect(redirectedUrl("/admin/login"));
    }

    @Test
    @DisplayName("로그인 화면은 누구나 볼 수 있고 CSRF 토큰 입력과 ?error·?expired 문구를 보여준다")
    void loginPageIsPublic() throws Exception {
        // 테스트가 csrf() 로 토큰을 직접 넣으면 폼에 토큰이 빠져도 통과한다. 실제 폼에 들어가는지 본다.
        mockMvc.perform(get("/admin/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("관리자 로그인")))
                .andExpect(content().string(Matchers.containsString("name=\"_csrf\"")));
        mockMvc.perform(get("/admin/login").param("error", ""))
                .andExpect(content().string(Matchers.containsString("아이디 또는 비밀번호가 올바르지 않습니다.")));
        mockMvc.perform(get("/admin/login").param("expired", ""))
                .andExpect(content().string(Matchers.containsString("세션이 만료됐어요. 다시 로그인해 주세요.")));
    }

    @Test
    @DisplayName("올바른 계정으로 로그인하면 항상 문의 목록으로 간다")
    void loginSucceeds() throws Exception {
        mockMvc.perform(formLogin("/admin/login").user("test-admin").password("test-admin-password"))
                .andExpect(redirectedUrl("/admin/inquiries"));
    }

    @Test
    @DisplayName("로그인 실패는 ?error 로 보내고 접속 IP 만 WARN 으로 남긴다")
    void loginFailsAndLogsIp(CapturedOutput output) throws Exception {
        mockMvc.perform(formLogin("/admin/login").user("test-admin").password("wrong-password"))
                .andExpect(redirectedUrl("/admin/login?error"));

        assertThat(output).contains("관리자 로그인 실패. ip=");
        assertThat(output).doesNotContain("wrong-password");
    }

    @Test
    @DisplayName("세션이 만료된 요청은 ?expired 로 보낸다")
    void expiredSessionRedirects() throws Exception {
        mockMvc.perform(get("/admin/inquiries").with(request -> {
                    request.setRequestedSessionId("expired-session-id");
                    request.setRequestedSessionIdValid(false);
                    return request;
                }))
                .andExpect(redirectedUrl("/admin/login?expired"));
    }

    @Test
    @DisplayName("로그아웃은 CSRF 토큰이 있어야 하고 로그인 화면으로 보낸다")
    void logoutRequiresCsrf() throws Exception {
        // 세션 없는 요청의 토큰 누락은 MissingCsrfTokenException 이고, invalidSessionUrl 이 설정돼 있으면
        // CsrfConfigurer 가 이를 InvalidSessionAccessDeniedHandler 로 보내 항상 ?expired 로 리다이렉트한다.
        mockMvc.perform(post("/admin/logout").with(user("test-admin")))
                .andExpect(redirectedUrl("/admin/login?expired"));

        mockMvc.perform(post("/admin/logout").with(user("test-admin")).with(csrf()))
                .andExpect(redirectedUrl("/admin/login"));
    }

    @Test
    @DisplayName("세션에 토큰이 있는데 다른 토큰으로 보내도 기본 403 화면이 아니라 ?expired 로 보낸다")
    void staleCsrfTokenRedirects() throws Exception {
        // 다른 탭에서 다시 로그인해 세션 토큰이 바뀐 뒤 옛 화면에서 저장하는 상황이다.
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(get("/admin/login").session(session)).andExpect(status().isOk());

        mockMvc.perform(post("/admin/login").session(session)
                        .param("username", "test-admin")
                        .param("password", "test-admin-password")
                        .param("_csrf", "stale-token"))
                .andExpect(redirectedUrl("/admin/login?expired"));
    }

    @Test
    @DisplayName("GET /admin 은 문의 목록으로 리다이렉트한다")
    void rootRedirects() throws Exception {
        mockMvc.perform(get("/admin").with(user("test-admin")))
                .andExpect(redirectedUrl("/admin/inquiries"));
    }

    @Test
    @DisplayName("앱 API 는 여전히 JWT 체인이 막아 토큰 없이 401 이다")
    void apiChainUnaffected() throws Exception {
        mockMvc.perform(get("/api/v1/inquiries"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("UserDetailsService 는 관리자 계정 Bean 하나뿐이라 Boot 기본 계정이 만들어지지 않는다")
    void onlyAdminUserDetailsService() {
        assertThat(applicationContext.getBeansOfType(UserDetailsService.class))
                .containsOnlyKeys("adminUserDetailsService");
    }
}
