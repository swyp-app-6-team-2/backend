package com.star_pick.starpick.domain.cooking.service;

import com.star_pick.starpick.domain.cooking.repository.CookHistoryRepository;
import com.star_pick.starpick.domain.upload.domain.UploadPurpose;
import com.star_pick.starpick.domain.upload.service.UploadService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recipe 삭제 트랜잭션에 참여하는 Cooking 의 공개 정리 UseCase.
 *
 * <p><b>{@link CookHistoryService} 와 반드시 다른 Bean 이다.</b> 생성·조회 Service 는 소유권 확인
 * 때문에 이미 {@code RecipeService} 를 주입받고 있어, 여기에 정리까지 얹으면 두 Service 가 서로를
 * 생성자 주입해 애플리케이션이 기동하지 못한다. 근거는 {@code docs/specs/cooking.md} §3.4.
 */
@Service
@RequiredArgsConstructor
public class CookHistoryCleanupService {

    private final CookHistoryRepository cookHistoryRepository;

    private final UploadService uploadService;

    /**
     * Recipe 에 딸린 CookHistory 와 완성 사진을 정리한다. <b>호출자의 트랜잭션에 참여한다.</b>
     *
     * <p>세 줄의 순서가 계약이다. 커밋 이후에 지울 {@code photoKey} 를 CookHistory 행에서만 알 수
     * 있어, 행을 지우기 전에 먼저 읽어야 한다. CookHistory 삭제를 {@code ON DELETE CASCADE} 에
     * 맡기지 않는 이유도 같다 — 그러면 읽을 기회 자체가 없다.
     *
     * <p>소유권은 호출자(Recipe)가 행 잠금과 함께 이미 확인했다. {@code userId} 를 넘기는 것은
     * Upload 가 소유자와 용도를 한 번 더 확인하기 때문이다.
     */
    @Transactional
    public void deleteByRecipe(Long userId, Long recipeId) {
        List<String> photoKeys = cookHistoryRepository.findPhotoKeysByRecipeId(recipeId);

        uploadService.releaseAndDeleteFiles(userId, photoKeys, UploadPurpose.COOK_HISTORY_PHOTO);
        cookHistoryRepository.deleteByRecipeId(recipeId);
    }
}
