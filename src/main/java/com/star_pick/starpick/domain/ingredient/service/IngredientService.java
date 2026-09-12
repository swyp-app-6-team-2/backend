package com.star_pick.starpick.domain.ingredient.service;

import com.star_pick.starpick.domain.ingredient.controller.response.IngredientListResponse;
import com.star_pick.starpick.domain.ingredient.domain.Ingredient;
import com.star_pick.starpick.domain.ingredient.repository.IngredientRepository;
import java.util.Collection;
import java.util.Comparator;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IngredientService {

    // 카테고리 안에서는 이름 가나다순이다. 완성형 한글은 유니코드 순이 곧 가나다순이라
    // Collator 없이 자연 순서로 충분하다. code 는 시트 입력 순서라 사용자에게 의미가 없다.
    private static final Comparator<Ingredient> DISPLAY_ORDER =
            Comparator.comparing(Ingredient::getCategory)
                    .thenComparing(Ingredient::getName);

    private final IngredientRepository ingredientRepository;
    private final String iconBaseUrl;

    public IngredientService(
            IngredientRepository ingredientRepository,
            @Value("${starpick.ingredient.icon-base-url}") String iconBaseUrl) {
        this.ingredientRepository = ingredientRepository;
        // 운영 설정에 끝 슬래시가 들어오면 전건이 `//images/...` 가 된다. 여러 개가 붙어도
        // 전부 걷어내도록 주입 시점에 한 번만 다듬는다.
        this.iconBaseUrl = iconBaseUrl.replaceAll("/+$", "");
    }

    @Transactional(readOnly = true)
    public IngredientListResponse findActiveIngredients() {
        return IngredientListResponse.from(
                ingredientRepository.findAllByActiveTrue().stream()
                        .sorted(DISPLAY_ORDER)
                        .toList(),
                iconBaseUrl);
    }

    @Transactional(readOnly = true)
    public boolean existsAll(Collection<Long> ingredientIds) {
        if (ingredientIds.isEmpty()) {
            return true;
        }
        Set<Long> uniqueIds = Set.copyOf(ingredientIds);
        return ingredientRepository.countByIdIn(uniqueIds) == uniqueIds.size();
    }
}
