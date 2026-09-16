package com.star_pick.starpick.domain.user.service;

import com.star_pick.starpick.domain.user.entity.User;
import com.star_pick.starpick.domain.user.exception.UserRecipeSlotErrorCode;
import com.star_pick.starpick.domain.user.repository.UserRepository;
import com.star_pick.starpick.global.exception.BusinessException;
import com.star_pick.starpick.global.exception.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recipe·Ingestion·Ad 도메인이 레시피 저장 슬롯을 확인하고 쓰는 경계.
 * User는 다른 도메인 엔티티를 직접 참조하지 않고, 이 카운터로만 상태를 안다.
 */
@Service
@RequiredArgsConstructor
public class UserRecipeStatsService {

    private final UserRepository users;

    /**
     * 레시피를 새로 저장할 때 슬롯을 하나 쓴다. 남은 슬롯이 없으면 거절한다.
     *
     * <p>사용자 행을 잠가 같은 사용자의 동시 저장을 줄 세운다. 잠그지 않으면 슬롯 1개로 두 요청이
     * 함께 통과한다. 호출자의 트랜잭션에 참여하므로 저장이 실패하면 차감도 롤백된다.
     */
    @Transactional
    public void onRecipeCreated(Long userId) {
        User user = users.findByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.AUTHENTICATION_REQUIRED));
        requireRemainingSlot(user);
        user.recordRecipeCreated();
    }

    /** 분석 요청 전에 남은 슬롯만 확인한다. 쓰는 것은 저장할 때다. */
    @Transactional(readOnly = true)
    public void requireRemainingSlot(Long userId) {
        User user = users.findById(userId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.AUTHENTICATION_REQUIRED));
        requireRemainingSlot(user);
    }

    private static void requireRemainingSlot(User user) {
        if (user.getDeletedAt() != null) throw new BusinessException(CommonErrorCode.AUTHENTICATION_REQUIRED);
        if (user.getRemainingRecipeSlots() <= 0) {
            throw new BusinessException(UserRecipeSlotErrorCode.RECIPE_SLOT_EXCEEDED);
        }
    }

    /**
     * 검증된 광고 보상만큼 저장 슬롯 한도를 늘린다. 호출자(Ad 도메인)의 지급 트랜잭션에 참여한다.
     *
     * <p>잠금 순서는 {@code users → daily_quota → session}(REWARDED_AD_SSV.md §7)이다. 호출자가 이미
     * 세션·일일 상태를 잠근 뒤 이 메서드를 불러야 하며, 여기서는 사용자 행만 잠근다.
     */
    @Transactional
    public void onAdRewardGranted(Long userId, int amount) {
        User user = users.findByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.AUTHENTICATION_REQUIRED));
        if (user.getDeletedAt() != null) throw new BusinessException(CommonErrorCode.AUTHENTICATION_REQUIRED);
        user.increaseRecipeSlotLimit(amount);
    }
}
