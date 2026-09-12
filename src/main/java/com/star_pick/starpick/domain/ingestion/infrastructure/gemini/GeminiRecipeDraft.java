package com.star_pick.starpick.domain.ingestion.infrastructure.gemini;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
record GeminiRecipeDraft(
        String verdict,
        String title,
        String categoryCode,
        Integer cookTimeMinutes,
        Integer servings,
        List<Ingredient> ingredients,
        List<Step> steps) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Ingredient(String name, String amountText) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Step(String content) {
    }
}
