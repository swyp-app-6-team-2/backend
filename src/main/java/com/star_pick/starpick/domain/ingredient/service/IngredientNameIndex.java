package com.star_pick.starpick.domain.ingredient.service;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public class IngredientNameIndex {

    /** 색인 한 번 만들 때 이름·별칭 수백 개를 정규화한다. 매번 컴파일하지 않도록 상수로 둔다. */
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

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
        String normalized = WHITESPACE.matcher(value.toLowerCase(Locale.ROOT)).replaceAll("");
        return normalized.isEmpty() ? null : normalized;
    }
}
