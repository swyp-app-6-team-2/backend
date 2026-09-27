package com.star_pick.starpick.domain.ingredient.service;

import com.star_pick.starpick.domain.ingredient.controller.response.IngredientListResponse;
import com.star_pick.starpick.domain.ingredient.domain.Ingredient;
import com.star_pick.starpick.domain.ingredient.repository.IngredientRepository;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IngredientService {

    // 카테고리와 무관하게 이름 가나다순이다. 앱은 배열을 카테고리로 거르기만 하므로 카테고리 탭도
    // 가나다순이 유지된다. 완성형 한글은 유니코드 순이 곧 가나다순이라 Collator 없이 자연 순서로
    // 충분하다. DB collation 은 한글을 가나다순으로 정렬하지 않아 조회 뒤 여기서 정렬한다.
    // name 은 UNIQUE 가 아니라 id 로 동률을 끊는다.
    private static final Comparator<Ingredient> DISPLAY_ORDER =
            Comparator.comparing(Ingredient::getName)
                    .thenComparing(Ingredient::getId);

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

    /** 이름·별칭이 정확히 하나의 활성 재료를 가리킬 때만 자동 연결하는 색인을 만든다. */
    @Transactional(readOnly = true)
    public IngredientNameIndex loadNameIndex() {
        Map<String, Set<Long>> candidates = new HashMap<>();
        for (Ingredient ingredient : ingredientRepository.findAllByActiveTrue()) {
            addCandidate(candidates, ingredient.getName(), ingredient.getId());
            for (String alias : ingredient.getAliases()) {
                addCandidate(candidates, alias, ingredient.getId());
            }
        }
        Map<String, Long> unique = new HashMap<>();
        candidates.forEach((name, ids) -> {
            if (ids.size() == 1) {
                unique.put(name, ids.iterator().next());
            }
        });
        return new IngredientNameIndex(unique);
    }

    private void addCandidate(Map<String, Set<Long>> candidates, String rawName, Long id) {
        String name = IngredientNameIndex.normalize(rawName);
        if (name != null) {
            candidates.computeIfAbsent(name, ignored -> new HashSet<>()).add(id);
        }
    }
}
