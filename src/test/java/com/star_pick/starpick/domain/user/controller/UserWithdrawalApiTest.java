package com.star_pick.starpick.domain.user.controller;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.star_pick.starpick.domain.auth.service.AuthService;
import com.star_pick.starpick.domain.upload.domain.UploadPurpose;
import com.star_pick.starpick.domain.upload.service.UploadService;
import com.star_pick.starpick.domain.user.service.UserWithdrawalService;
import com.star_pick.starpick.domain.user.service.UserWithdrawalRecovery;
import com.star_pick.starpick.global.security.jwt.JwtProvider;
import com.star_pick.starpick.support.*;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class UserWithdrawalApiTest {
    private static final long OWNER = 107001L;
    private static final long OTHER = 107002L;
    private static final String PATH = "/api/v1/users/me";
    @Autowired MockMvc mvc;
    @Autowired TestFixtures fixtures;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtProvider jwt;
    @Autowired AuthService auth;
    @Autowired UserWithdrawalService withdrawals;
    @Autowired UploadService uploads;
    @Autowired FakeObjectStorage storage;
    @Autowired Clock clock;

    @BeforeEach void setUp() {
        fixtures.reset();
        fixtures.seedUser(OWNER);
        fixtures.seedUser(OTHER);
    }

    @AfterEach void clean() {
        jdbc.execute("drop table if exists withdrawal_failure_guard");
        jdbc.update("update users set deleted_at = null where user_id in (?, ?)", OWNER, OTHER);
        jdbc.update("delete from social_credentials where user_id in (?, ?)", OWNER, OTHER);
    }

    private String bearer(long id) { return "Bearer " + jwt.generateTokens(id).accessToken(); }
    private int count(String table, long id) {
        return jdbc.queryForObject("select count(*) from " + table + " where user_id = ?", Integer.class, id);
    }
    private void mark(long id) { jdbc.update("update users set deleted_at = now() where user_id = ?", id); }

    @Test void deletesOwnedDataAndAnonymizesTransactionsWithoutTouchingOtherUser() throws Exception {
        var tokens = auth.issueAndStore(OWNER);
        long recipe = fixtures.saveRecipeWithChildren(OWNER);
        String cover = fixtures.attachCover(OWNER, recipe);
        String cooked = fixtures.saveCookHistoryWithPhoto(OWNER, recipe);
        fixtures.saveImageRecipe(OWNER);
        var instagram = fixtures.saveReadyInstagramJob(OWNER);
        String profile = fixtures.uploadedKey(OWNER, UploadPurpose.PROFILE_IMAGE);
        fixtures.saveInquiry(OWNER, "탈퇴 문의");
        fixtures.saveRecipe(OTHER);
        jdbc.update("insert into profiles (user_id,nickname,created_at,updated_at) values (?, '탈퇴', now(),now())", OWNER);
        jdbc.update("insert into social_credentials (user_id,provider,social_uid,created_at) values (?,'GOOGLE','withdraw-test',now())", OWNER);
        jdbc.update("insert into user_ingredient(user_id,ingredient_id) select ?,id from ingredient limit 1", OWNER);
        jdbc.update("insert into notification_setting(user_id,enabled) values (?,true)", OWNER);
        jdbc.update("insert into push_token(user_id,token,platform,active) values (?,'withdraw-token','ANDROID',true)", OWNER);
        jdbc.update("insert into push_log(user_id,push_token_id,scheduled_at,status) select ?,id,now(),'SENT' from push_token where user_id=?", OWNER,OWNER);
        UUID session = UUID.randomUUID();
        jdbc.update("""
                insert into ad_reward_session(id,user_id,request_id,platform,expected_ad_unit,quota_date,expires_at,verification_deadline)
                values (?,?,'withdraw','ANDROID','unit',current_date,now()+interval '30 minutes',now()+interval '24 hours')
                """, session,OWNER);
        jdbc.update("insert into ad_reward_daily_quota(user_id,quota_date,reserved_count) values (?,current_date,1)", OWNER);
        jdbc.update("""
                insert into ad_reward_transaction(transaction_id,session_id,user_id,status,ad_unit,reward_item,
                received_reward_amount,reward_event_at,received_at,processed_at)
                values ('withdraw-transaction',?,?,'REJECTED','unit','recipe_slot',2,now(),now(),now())
                """,session,OWNER);

        mvc.perform(delete(PATH).header("Authorization","Bearer " + tokens.accessToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.message").value("회원 탈퇴가 완료되었습니다."))
                .andExpect(jsonPath("$.data").doesNotExist());
        for (String table : new String[]{"users","profiles","social_credentials","refresh_tokens","recipe",
                "ingestion_job","upload_object","user_ingredient","inquiry","notification_setting","push_token",
                "push_log","ad_reward_session","ad_reward_daily_quota"}) {
            assertThat(count(table,OWNER)).as(table).isZero();
        }
        assertThat(count("recipe",OTHER)).isEqualTo(1);
        assertThat(jdbc.queryForMap("select user_id,session_id from ad_reward_transaction where transaction_id='withdraw-transaction'"))
                .containsEntry("user_id",null).containsEntry("session_id",null);
        assertThat(storage.contains(cover)).isFalse();
        assertThat(storage.contains(cooked)).isFalse();
        assertThat(storage.contains(profile)).isFalse();
        assertThat(storage.contains(instagram.getSourceThumbnailKey())).isFalse();
        assertThatThrownBy(() -> auth.refresh(tokens.refreshToken())).isInstanceOf(RuntimeException.class);
        mvc.perform(get(PATH).header("Authorization",bearer(OWNER))).andExpect(status().isUnauthorized());
        mvc.perform(delete(PATH).header("Authorization",bearer(OWNER))).andExpect(status().isUnauthorized());
    }

    @Test void requiresAuthentication() throws Exception {
        mvc.perform(delete(PATH)).andExpect(status().isUnauthorized());
        mvc.perform(delete(PATH).header("Authorization","Bearer invalid")).andExpect(status().isUnauthorized());
        mvc.perform(delete(PATH).header("Authorization",bearer(99999999L))).andExpect(status().isUnauthorized());
        assertThat(count("users",OWNER)).isEqualTo(1);
    }

    @Test void markedUserCanOnlyRetryWithdrawal() throws Exception {
        mark(OWNER);
        mvc.perform(get(PATH).header("Authorization",bearer(OWNER))).andExpect(status().isUnauthorized());
        mvc.perform(delete(PATH + "/ingredients").header("Authorization",bearer(OWNER))).andExpect(status().isUnauthorized());
        mvc.perform(delete(PATH).header("Authorization",bearer(OWNER))).andExpect(status().isOk());
    }

    @Test void failurePreservesMarkerAndCredentialsThenRecoveryCompletesWithoutToken() {
        auth.issueAndStore(OWNER);
        fixtures.saveRecipe(OWNER);
        jdbc.update("insert into social_credentials (user_id,provider,social_uid,created_at) values (?,'GOOGLE','withdraw-retry',now())",OWNER);
        jdbc.execute("create table withdrawal_failure_guard (user_id bigint references users(user_id))");
        jdbc.update("insert into withdrawal_failure_guard values (?)",OWNER);
        assertThatThrownBy(() -> withdrawals.withdraw(OWNER)).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("select deleted_at from users where user_id=?", java.sql.Timestamp.class,OWNER)).isNotNull();
        assertThat(count("recipe",OWNER)).isZero();
        assertThat(count("refresh_tokens",OWNER)).isZero();
        assertThat(count("social_credentials",OWNER)).isEqualTo(1);
        jdbc.update("delete from withdrawal_failure_guard");
        new UserWithdrawalRecovery(jdbc,withdrawals,clock,20).recoverBatch();
        assertThat(count("users",OWNER)).isZero();
    }

    @Test void storageFailureDoesNotRollBackDatabaseDeletion() throws Exception {
        fixtures.uploadedKey(OWNER,UploadPurpose.PROFILE_IMAGE);
        storage.startFailing();
        mvc.perform(delete(PATH).header("Authorization",bearer(OWNER))).andExpect(status().isOk());
        assertThat(count("users",OWNER)).isZero();
    }

    @Test void admittedWriteCannotRecreateDataAfterMarking() {
        mark(OWNER);
        assertThatThrownBy(() -> uploads.issueUploadUrl(OWNER,UploadPurpose.PROFILE_IMAGE,"image/jpeg"))
                .isInstanceOf(com.star_pick.starpick.global.exception.BusinessException.class);
        assertThat(count("upload_object",OWNER)).isZero();
        assertThatThrownBy(() -> auth.issueAndStore(OWNER)).isInstanceOf(RuntimeException.class);
    }

    @Test void recoveryNeverDeletesActiveUser() {
        assertThatThrownBy(() -> withdrawals.resume(OWNER)).isInstanceOf(IllegalStateException.class);
        assertThat(count("users",OWNER)).isEqualTo(1);
    }
    @Test void signupAfterWithdrawalStartsWithNewIdentityAndDefaultSlots() {
        String signupToken = jwt.generateSignupToken(
                com.star_pick.starpick.domain.user.entity.Provider.GOOGLE,"withdraw-signup",null);
        var request = new com.star_pick.starpick.domain.auth.dto.SignupRequest(signupToken,true,true,true,false,false);
        var first = auth.signup(request);
        withdrawals.withdraw(first.userId());
        var second = auth.signup(request);
        assertThat(second.userId()).isNotEqualTo(first.userId());
        assertThat(jdbc.queryForObject("select recipe_slot_limit from users where user_id=?",Integer.class,second.userId())).isEqualTo(10);
        assertThat(jdbc.queryForObject("select cumulative_recipe_count from users where user_id=?",Integer.class,second.userId())).isZero();
        withdrawals.withdraw(second.userId());
    }

    @Test void concurrentRecoveryRunsAreIdempotent() throws Exception {
        for (int i=0;i<25;i++) fixtures.saveRecipe(OWNER);
        mark(OWNER);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var ready = new java.util.concurrent.CountDownLatch(2);
            var start = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<Void> task = () -> {
                ready.countDown();
                start.await();
                withdrawals.resume(OWNER);
                return null;
            };
            var one=executor.submit(task);
            var two=executor.submit(task);
            assertThat(ready.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();
            one.get(20,java.util.concurrent.TimeUnit.SECONDS);
            two.get(20,java.util.concurrent.TimeUnit.SECONDS);
        }
        assertThat(count("users",OWNER)).isZero();
        assertThat(count("recipe",OWNER)).isZero();
    }

    @Test void recoveryCursorMovesPastFailingAccount() {
        mark(OWNER);
        mark(OTHER);
        jdbc.execute("create table withdrawal_failure_guard (user_id bigint references users(user_id))");
        jdbc.update("insert into withdrawal_failure_guard values (?)",OWNER);
        var recovery = new UserWithdrawalRecovery(jdbc,withdrawals,clock,1);
        recovery.recoverBatch();
        assertThat(count("users",OWNER)).isEqualTo(1);
        recovery.recoverBatch();
        assertThat(count("users",OTHER)).isZero();
        jdbc.update("delete from withdrawal_failure_guard");
        recovery.recoverBatch(); // 커서를 처음으로 돌린다.
        recovery.recoverBatch();
        assertThat(count("users",OWNER)).isZero();
    }

}
