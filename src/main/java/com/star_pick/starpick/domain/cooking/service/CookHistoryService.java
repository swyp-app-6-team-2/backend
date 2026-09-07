package com.star_pick.starpick.domain.cooking.service;

import com.star_pick.starpick.domain.cooking.controller.request.CookHistoryCreateRequest;
import com.star_pick.starpick.domain.cooking.controller.response.CookHistoryResponse;
import com.star_pick.starpick.domain.cooking.domain.CookHistory;
import com.star_pick.starpick.domain.cooking.exception.CookingErrorCode;
import com.star_pick.starpick.domain.cooking.repository.CookHistoryRepository;
import com.star_pick.starpick.domain.recipe.service.RecipeService;
import com.star_pick.starpick.domain.upload.domain.UploadPurpose;
import com.star_pick.starpick.domain.upload.service.AttachOutcome;
import com.star_pick.starpick.domain.upload.service.UploadService;
import com.star_pick.starpick.global.exception.BusinessException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 요리 완료 기록 유스케이스.
 *
 * <p>Recipe 존재·소유권은 {@code RecipeService} 가 공개한 경계로만 확인한다. Recipe 의
 * Repository·Entity·응답 DTO 를 쓰지 않는다(CLAUDE.md §4).
 */
@Service
@RequiredArgsConstructor
public class CookHistoryService {

    private final CookHistoryRepository cookHistoryRepository;

    private final RecipeService recipeService;

    private final UploadService uploadService;

    /**
     * 조리 완료 기록을 남긴다.
     *
     * <p>소유권 확인, 사진 연결, 저장이 한 트랜잭션이다. 저장이 실패하면 사진 연결도 롤백되어
     * {@code attachedAt} 이 남지 않는다.
     *
     * <p>생성 시 Recipe 행을 잠그지 않는다(cooking.md §4.2). 확인 직후 Recipe 가 삭제되는
     * 경쟁은 허용하며, 그 결과 남는 데이터는 spec §3.4 에 적었다.
     */
    @Transactional
    public void create(Long userId, Long recipeId, CookHistoryCreateRequest request) {
        recipeService.requireOwnedRecipe(userId, recipeId);

        String photoKey = request.photoKey();
        if (photoKey != null) {
            attachPhoto(userId, photoKey);
        }

        try {
            // 커밋까지 미루면 제약 위반이 이 메서드 밖에서 터져 번역할 수 없다.
            cookHistoryRepository.saveAndFlush(CookHistory.create(recipeId, photoKey, request.memo()));
        } catch (DataIntegrityViolationException e) {
            throw translate(e);
        }
    }

    /**
     * Recipe 의 조리 완료 이력을 최근 순으로 조회한다.
     *
     * <p><b>{@code @Transactional} 을 붙이지 않는 것이 의도다.</b> 소유권 확인과 목록 조회는
     * 각자 짧은 읽기 트랜잭션에서 끝나고, 조회 URL 서명은 커넥션을 쥐지 않은 채 수행한다.
     * 서명에는 저장소 클라이언트 타임아웃이 걸리지 않는데(upload.md §3.4) 이력 조회는
     * 페이지네이션이 없어 N 건을 순차로 서명하기 때문이다. CookHistory 에는 지연 로딩
     * 컬렉션이 없어 트랜잭션 밖에서 응답을 조립해도 안전하다.
     */
    public List<CookHistoryResponse> getCookHistories(Long userId, Long recipeId) {
        recipeService.requireOwnedRecipe(userId, recipeId);

        return cookHistoryRepository.findByRecipeIdOrderByCookedAtDescIdDesc(recipeId).stream()
                .map(history ->
                        CookHistoryResponse.from(history, uploadService.getViewUrl(userId, history.getPhotoKey())))
                .toList();
    }

    private void attachPhoto(Long userId, String photoKey) {
        AttachOutcome outcome =
                uploadService.attach(userId, photoKey, UploadPurpose.COOK_HISTORY_PHOTO);

        switch (outcome) {
            case INVALID -> throw new BusinessException(CookingErrorCode.COOK_HISTORY_PHOTO_INVALID);
            case ALREADY_ATTACHED -> throw new BusinessException(CookingErrorCode.COOK_HISTORY_PHOTO_ALREADY_USED);
            case ATTACHED -> { }
        }
    }

    /**
     * 제약 이름으로만 분기한다. 무결성 위반을 뭉뚱그려 409 로 바꾸면 무관한 오류까지 409 가 되어
     * 계약이 깨진다. 사진 UNIQUE 가 아닌 위반은 그대로 500 으로 나간다.
     */
    private RuntimeException translate(DataIntegrityViolationException e) {
        if (e.getCause() instanceof ConstraintViolationException violation
                && CookHistory.PHOTO_KEY_UNIQUE.equalsIgnoreCase(violation.getConstraintName())) {
            return new BusinessException(CookingErrorCode.COOK_HISTORY_PHOTO_ALREADY_USED);
        }
        return e;
    }
}
