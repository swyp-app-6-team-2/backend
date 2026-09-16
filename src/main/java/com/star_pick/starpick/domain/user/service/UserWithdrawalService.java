package com.star_pick.starpick.domain.user.service;

import com.star_pick.starpick.domain.ad.service.AdRewardCleanupService;
import com.star_pick.starpick.domain.auth.service.AuthService;
import com.star_pick.starpick.domain.ingestion.service.IngestionJobPurger;
import com.star_pick.starpick.domain.inquiry.service.InquiryCleanupService;
import com.star_pick.starpick.domain.notification.service.NotificationCleanupService;
import com.star_pick.starpick.domain.recipe.service.RecipeService;
import com.star_pick.starpick.domain.upload.service.UploadService;
import com.star_pick.starpick.domain.user.repository.UserRepository;
import com.star_pick.starpick.global.exception.BusinessException;
import com.star_pick.starpick.global.exception.CommonErrorCode;
import java.time.Clock;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** 전체 요청에는 트랜잭션을 걸지 않는다. 단계 커밋 사이에 프로세스가 종료돼도 재시도한다. */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserWithdrawalService {
    private final UserRepository users;
    private final AuthService auth;
    private final RecipeService recipes;
    private final IngestionJobPurger ingestion;
    private final UploadService uploads;
    private final NotificationCleanupService notifications;
    private final InquiryCleanupService inquiries;
    private final AdRewardCleanupService ads;
    private final TransactionTemplate transactions;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public void withdraw(Long userId) {
        transactions.executeWithoutResult(status -> {
            var user = users.findByIdForUpdate(userId)
                    .orElseThrow(() -> new BusinessException(CommonErrorCode.AUTHENTICATION_REQUIRED));
            user.beginWithdrawal(clock.instant());
            auth.revokeForWithdrawal(userId);
        });
        log.info("회원 탈퇴 접수. userId={}", userId);
        resume(userId);
    }

    /** 서버 복구 전용. 정상 계정은 아래 각 단계의 잠금 안에서 거절한다. */
    public void resume(Long userId) {
        long started = System.nanoTime();
        while (step(userId, "recipes", () -> {
            var ids = recipes.findWithdrawalBatch(userId);
            ids.forEach(id -> recipes.deleteForWithdrawal(userId, id));
            return !ids.isEmpty();
        })) { }
        while (step(userId, "ingestion", () -> {
            var ids = ingestion.findWithdrawalBatch(userId);
            ids.forEach(ingestion::purgeOne);
            return !ids.isEmpty();
        })) { }
        while (step(userId, "uploads", () -> uploads.deleteNextForUser(userId))) { }
        step(userId, "notifications", () -> { notifications.deleteAllForUser(userId); return false; });
        step(userId, "ingredients", () -> {
            jdbc.update("delete from user_ingredient where user_id = ?", userId); return false;
        });
        step(userId, "inquiries", () -> { inquiries.deleteAllForUser(userId); return false; });
        step(userId, "ads", () -> { ads.deleteAllForUser(userId); return false; });
        step(userId, "account", () -> {
            auth.revokeForWithdrawal(userId);
            auth.deleteCredentialsForWithdrawal(userId);
            jdbc.update("delete from profiles where user_id = ?", userId);
            users.deleteById(userId);
            users.flush();
            return false;
        });
        log.info("회원 탈퇴 완료. userId={}, elapsedMs={}", userId, (System.nanoTime() - started) / 1_000_000);
    }

    private boolean step(Long userId, String name, Supplier<Boolean> action) {
        try {
            return Boolean.TRUE.equals(transactions.execute(status -> {
                var user = users.findByIdForUpdate(userId).orElse(null);
                if (user == null) return false;
                if (user.getDeletedAt() == null) {
                    throw new IllegalStateException("탈퇴 접수되지 않은 사용자는 정리할 수 없습니다.");
                }
                return action.get();
            }));
        } catch (RuntimeException e) {
            log.error("회원 탈퇴 단계 실패. userId={}, stage={}", userId, name, e);
            throw e;
        }
    }
}
