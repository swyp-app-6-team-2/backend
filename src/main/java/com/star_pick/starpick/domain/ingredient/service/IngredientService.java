package com.star_pick.starpick.domain.ingredient.service;

import com.star_pick.starpick.domain.ingredient.controller.response.IngredientListResponse;
import com.star_pick.starpick.domain.ingredient.domain.Ingredient;
import com.star_pick.starpick.domain.ingredient.repository.IngredientRepository;
import java.util.Collection;
import java.util.Comparator;
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
}
