package com.star_pick.starpick.domain.user.controller;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.star_pick.starpick.domain.user.repository.ProfileRepository;
import com.star_pick.starpick.domain.upload.domain.UploadPurpose;
import com.star_pick.starpick.domain.upload.service.UploadService;
import com.star_pick.starpick.global.security.jwt.JwtProvider;
import com.star_pick.starpick.support.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import java.util.concurrent.*;

@IntegrationTest
class ProfileApiTest {
    private static final long OWNER = 996001L, OTHER = 996002L;
    private static final String PATH = "/api/v1/users/me/profile";
    @Autowired MockMvc mvc;
    @Autowired JwtProvider jwt;
    @Autowired ProfileRepository profiles;
    @Autowired TestFixtures fixtures;
    @Autowired UploadService uploads;
    @Autowired FakeObjectStorage storage;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;

    @BeforeEach void setUp() {
        fixtures.reset();
        fixtures.seedUser(OWNER);
        fixtures.seedUser(OTHER);
        jdbc.update("delete from profiles where user_id in (?, ?)", OWNER, OTHER);
    }
    @AfterEach void restore() {
        jdbc.update("update users set deleted_at = null where user_id = ?", OWNER);
    }
    private ResultActions patchBody(long id, String body) throws Exception {
        return mvc.perform(patch(PATH).header("Authorization", "Bearer " + jwt.generateTokens(id).accessToken())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }
    private ResultActions patchImage(String nickname, String key) throws Exception {
        var body = new HashMap<String,Object>();
        body.put("nickname", nickname); body.put("profileImageKey", key);
        return patchBody(OWNER, json.writeValueAsString(body));
    }
    private String image(long id, UploadPurpose purpose) {
        String key = uploads.issueUploadUrl(id, purpose, "image/png").objectKey();
        storage.putObject(key);
        return key;
    }

    @Test void nicknameOnlyCreatesMissingProfileAndAllowsDuplicateNickname() throws Exception {
        patchBody(OWNER, "{\"nickname\":\"별따먹자\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(OWNER))
                .andExpect(jsonPath("$.data.profileImageUrl").value(org.hamcrest.Matchers.nullValue()));
        patchBody(OTHER, "{\"nickname\":\"별따먹자\"}").andExpect(status().isOk());
        assertThat(profiles.findByUser_UserId(OWNER).orElseThrow().getNickname()).isEqualTo("별따먹자");
    }

    @Test void imageCanBeSetOmittedRetriedReplacedAndRemoved() throws Exception {
        String first = image(OWNER, UploadPurpose.PROFILE_IMAGE);
        patchImage("닉네임", first).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.profileImageUrl").value(FakeObjectStorage.VIEW_URL_PREFIX + first));
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + jwt.generateTokens(OWNER).accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.profileImageUrl").value(FakeObjectStorage.VIEW_URL_PREFIX + first));
        patchBody(OWNER, "{\"nickname\":\"변경\"}").andExpect(status().isOk());
        assertThat(profiles.findByUser_UserId(OWNER).orElseThrow().getProfileImageKey()).isEqualTo(first);
        patchImage("변경", first).andExpect(status().isOk());
        assertThat(storage.contains(first)).isTrue();
        String second = image(OWNER, UploadPurpose.PROFILE_IMAGE);
        patchImage("교체", second).andExpect(status().isOk());
        assertThat(storage.contains(first)).isFalse();
        assertThat(storage.contains(second)).isTrue();
        patchImage("제거", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.profileImageUrl").value(org.hamcrest.Matchers.nullValue()));
        assertThat(storage.contains(second)).isFalse();
        assertThat(profiles.findByUser_UserId(OWNER).orElseThrow().getProfileImageKey()).isNull();
    }

    @Test void rejectsForeignWrongPurposeMissingAndAlreadyAttachedImagesWithoutChangingProfile() throws Exception {
        String current = image(OWNER, UploadPurpose.PROFILE_IMAGE);
        patchImage("원본", current).andExpect(status().isOk());
        String used = image(OWNER, UploadPurpose.PROFILE_IMAGE);
        uploads.attach(OWNER, used, UploadPurpose.PROFILE_IMAGE);
        String notUploaded = uploads.issueUploadUrl(OWNER, UploadPurpose.PROFILE_IMAGE, "image/png").objectKey();
        for (String invalid : List.of(image(OTHER, UploadPurpose.PROFILE_IMAGE), image(OWNER, UploadPurpose.RECIPE_COVER),
                used, notUploaded, "unknown", "   ")) {
            patchImage("실패", invalid).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.data.code").value("PROFILE_IMAGE_INVALID"));
        }
        var profile = profiles.findByUser_UserId(OWNER).orElseThrow();
        assertThat(profile.getNickname()).isEqualTo("원본");
        assertThat(profile.getProfileImageKey()).isEqualTo(current);
        assertThat(storage.contains(current)).isTrue();
    }

    @Test void validationRejectsMissingBlankLongNicknameAndMalformedBody() throws Exception {
        for (String body : List.of("{}", "{\"nickname\":null}", "{\"nickname\":\" \"}", "{\"nickname\":\"1234567\"}",
                "{\"nickname\":\"정상\",\"profileImageKey\":\"\"}")) {
            patchBody(OWNER, body).andExpect(status().isBadRequest()).andExpect(jsonPath("$.data.code").value("REQUEST_VALIDATION_FAILED"));
        }
        patchBody(OWNER, "{").andExpect(status().isBadRequest());
        patchBody(OWNER, "{\"nickname\":\"별!@123\"}").andExpect(status().isOk());
    }

    @Test void invalidAuthenticationAndUserAreRejected() throws Exception {
        mvc.perform(patch(PATH).contentType(MediaType.APPLICATION_JSON).content("{\"nickname\":\"닉\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(patch(PATH).header("Authorization", "Bearer " + jwt.generateTokens(OWNER).refreshToken())
                .contentType(MediaType.APPLICATION_JSON).content("{\"nickname\":\"닉\"}"))
                .andExpect(status().isUnauthorized());
        patchBody(Long.MAX_VALUE, "{\"nickname\":\"닉\"}").andExpect(status().isUnauthorized());
        jdbc.update("update users set deleted_at = now() where user_id = ?", OWNER);
        patchBody(OWNER, "{\"nickname\":\"닉\"}").andExpect(status().isUnauthorized());
    }

    @Test void storageFailureRollsBackNicknameAndKeepsOldImage() throws Exception {
        String old = image(OWNER, UploadPurpose.PROFILE_IMAGE);
        patchImage("기존", old).andExpect(status().isOk());
        String next = image(OWNER, UploadPurpose.PROFILE_IMAGE);
        storage.startFailing();
        patchImage("실패", next).andExpect(status().isInternalServerError());
        var profile = profiles.findByUser_UserId(OWNER).orElseThrow();
        assertThat(profile.getNickname()).isEqualTo("기존");
        assertThat(profile.getProfileImageKey()).isEqualTo(old);
        assertThat(storage.contains(old)).isTrue();
    }

    @Test void legacyUrlIsPreservedOnOmissionAndClearedOnExplicitNull() throws Exception {
        patchBody(OWNER, "{\"nickname\":\"기존\"}").andExpect(status().isOk());
        jdbc.update("update profiles set profile_image_url = ? where user_id = ?", "https://legacy.example/image.png", OWNER);
        patchBody(OWNER, "{\"nickname\":\"유지\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.profileImageUrl").value("https://legacy.example/image.png"));
        patchImage("삭제", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.profileImageUrl").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test void simultaneousFirstUpdatesCreateOneProfile() throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(4)) {
            var futures = new ArrayList<Future<Integer>>();
            for (int i = 0; i < 4; i++) futures.add(executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("timeout");
                return patchBody(OWNER, "{\"nickname\":\"동시\"}").andReturn().getResponse().getStatus();
            }));
            start.countDown();
            for (var f : futures) assertThat(f.get(20, TimeUnit.SECONDS)).isEqualTo(200);
        }
        assertThat(jdbc.queryForObject("select count(*) from profiles where user_id = ?", Long.class, OWNER)).isEqualTo(1L);
    }

    @Test void uploadEndpointIssuesProfileKeyAndCanBeUsedForProfileUpdate() throws Exception {
        String body = mvc.perform(post("/api/v1/uploads/images")
                .header("Authorization", "Bearer " + jwt.generateTokens(OWNER).accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"purpose\":\"PROFILE_IMAGE\",\"contentType\":\"image/png\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.uploadUrl").isString())
                .andExpect(jsonPath("$.data.uploadHeaders").exists())
                .andReturn().getResponse().getContentAsString();
        String key = json.readTree(body).get("data").get("objectKey").asText();
        assertThat(key).startsWith("profile-images/" + OWNER + "/").endsWith(".png");
        patchImage("사진", key).andExpect(status().isBadRequest());
        storage.putObject(key);
        patchImage("사진", key).andExpect(status().isOk());
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + jwt.generateTokens(OWNER).accessToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.profileImageUrl").value(FakeObjectStorage.VIEW_URL_PREFIX + key));
    }

    @Test void nicknameLengthUsesUtf16AndRejectsMoreThanSixUnits() throws Exception {
        patchBody(OWNER, "{\"nickname\":\"😀😀😀\"}").andExpect(status().isOk());
        patchBody(OWNER, "{\"nickname\":\"😀😀😀a\"}").andExpect(status().isBadRequest());
    }
}
