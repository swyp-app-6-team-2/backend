package com.star_pick.starpick.domain.user.service;

import com.star_pick.starpick.domain.user.entity.User;
import com.star_pick.starpick.domain.user.repository.UserRepository;
import com.star_pick.starpick.global.exception.BusinessException;
import com.star_pick.starpick.global.exception.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recipe 도메인이 레시피 생성/삭제 시 호출하는 훅.
 * User는 Recipe 엔티티를 직접 참조하지 않고, 이 카운터로만 상태를 안다.
 */
@Service
@RequiredArgsConstructor
public class UserRecipeStatsService {

    private final UserRepository users;

    @Transactional
    public void onRecipeCreated(Long userId) {
        User user = users.findById(userId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.AUTHENTICATION_REQUIRED));
        user.increaseRecipeCounts();
    }

    @Transactional
    public void onRecipeDeleted(Long userId) {
        User user = users.findById(userId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.AUTHENTICATION_REQUIRED));
        user.decreaseActiveRecipeCount();
    }
}