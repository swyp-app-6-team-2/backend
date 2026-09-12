package com.star_pick.starpick.domain.ingredient.service;

import java.util.Map;
import java.util.Locale;

public class IngredientNameIndex {

    private final Map<String, Long> values;

    public IngredientNameIndex(Map<String, Long> values) {
        this.values = Map.copyOf(values);
    }

    public Long match(String rawName) {
        String key = normalize(rawName);
        return key == null ? null : values.get(key);
    }

    static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        return normalized.isEmpty() ? null : normalized;
    }
}
