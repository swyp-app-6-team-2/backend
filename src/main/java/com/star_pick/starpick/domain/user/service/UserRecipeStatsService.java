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
 * Recipe·Ingestion 도메인이 레시피 저장 슬롯을 확인하고 쓰는 경계.
 * User는 Recipe 엔티티를 직접 참조하지 않고, 이 카운터로만 상태를 안다.
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
        if (user.getRemainingRecipeSlots() <= 0) {
            throw new BusinessException(UserRecipeSlotErrorCode.RECIPE_SLOT_EXCEEDED);
        }
    }
}
