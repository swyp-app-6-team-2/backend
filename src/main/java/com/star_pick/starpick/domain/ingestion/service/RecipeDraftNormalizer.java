package com.star_pick.starpick.domain.ingestion.service;

import com.star_pick.starpick.domain.ingredient.service.IngredientNameIndex;
import com.star_pick.starpick.domain.ingestion.domain.RecipeDraft;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class RecipeDraftNormalizer {

    public RecipeDraft normalize(RecipeDraft raw, IngredientNameIndex index) {
        String title = bounded(raw.title());
        Integer cookTime = inRange(raw.cookTimeMinutes(), 1, 1440);
        Integer servings = raw.servings() != null && raw.servings() >= 1 ? raw.servings() : null;

        List<RecipeDraft.Ingredient> ingredients = raw.ingredients() == null ? List.of()
                : raw.ingredients().stream()
                        .filter(value -> value != null && text(value.name()) != null)
                        .map(value -> {
                            String name = bounded(value.name());
                            return new RecipeDraft.Ingredient(index.match(name), name, bounded(value.amountText()));
                        })
                        .toList();
        List<RecipeDraft.Step> steps = raw.steps() == null ? List.of()
                : raw.steps().stream()
                        .filter(value -> value != null && text(value.content()) != null)
                        .map(value -> new RecipeDraft.Step(value.content().trim()))
                        .toList();
        if (ingredients.isEmpty() && steps.isEmpty()) {
            return null;
        }
        return new RecipeDraft(title, raw.categoryCode(), cookTime, servings, ingredients, steps);
    }

    private Integer inRange(Integer value, int min, int max) {
        return value != null && value >= min && value <= max ? value : null;
    }

    private String bounded(String value) {
        String normalized = text(value);
        return normalized == null || normalized.length() <= 255
                ? normalized
                : normalized.substring(0, 255);
    }

    private String text(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
