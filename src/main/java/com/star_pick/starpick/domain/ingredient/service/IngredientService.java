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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class IngredientService {

    private static final Comparator<Ingredient> DISPLAY_ORDER =
            Comparator.comparing(Ingredient::getCategory)
                    .thenComparing(Ingredient::getCode);

    private final IngredientRepository ingredientRepository;

    @Transactional(readOnly = true)
    public IngredientListResponse findActiveIngredients() {
        return IngredientListResponse.from(
                ingredientRepository.findAllByActiveTrue().stream()
                        .sorted(DISPLAY_ORDER)
                        .toList());
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
