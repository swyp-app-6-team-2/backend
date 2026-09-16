package com.star_pick.starpick.admin.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.star_pick.starpick.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

/** 관리자 로그인 흐름. 계정은 테스트 설정의 test-admin / test-admin-password 다. */
@IntegrationTest
class AdminLoginFlowTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("로그인하면 원래 요청한 문의 상세로 돌아간다")
    void returnsToSavedRequest() throws Exception {
        MockHttpSession session = (MockHttpSession) mockMvc.perform(get("/admin/inquiries/12"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/login"))
                .andReturn()
                .getRequest()
                .getSession();

        mockMvc.perform(post("/admin/login").session(session).with(csrf())
                        .param("username", "test-admin")
                        .param("password", "test-admin-password"))
                // Security 7 은 저장된 요청을 되살릴 때 ?continue 를 붙인다.
                .andExpect(redirectedUrlPattern("**/admin/inquiries/12?continue"));
    }
}
