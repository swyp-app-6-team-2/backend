package com.star_pick.starpick.domain.user.service;

import com.star_pick.starpick.domain.ingredient.repository.IngredientRepository;
import com.star_pick.starpick.domain.user.dto.AddIngredientsResponse;
import com.star_pick.starpick.domain.user.exception.UserIngredientErrorCode;
import com.star_pick.starpick.domain.user.repository.UserIngredientRepository;
import com.star_pick.starpick.domain.user.repository.UserRepository;
import com.star_pick.starpick.global.exception.BusinessException;
import com.star_pick.starpick.global.exception.CommonErrorCode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserIngredientService {
    private final UserRepository users;
    private final IngredientRepository ingredients;
    private final UserIngredientRepository owned;
    private final String iconBaseUrl;

    public UserIngredientService(UserRepository users, IngredientRepository ingredients,
            UserIngredientRepository owned, @Value("${starpick.ingredient.icon-base-url}") String iconBaseUrl) {
        this.users = users;
        this.ingredients = ingredients;
        this.owned = owned;
        this.iconBaseUrl = iconBaseUrl.replaceAll("/+$", "");
    }

    @Transactional
    public AddIngredientsResponse add(Long userId, List<Long> ingredientIds) {
        var user = users.findByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.AUTHENTICATION_REQUIRED));
        if (user.getDeletedAt() != null) throw new BusinessException(CommonErrorCode.AUTHENTICATION_REQUIRED);
        var newIds = new LinkedHashSet<>(ingredientIds);
        newIds.removeAll(new HashSet<>(owned.findIngredientIds(userId)));
        if (newIds.isEmpty()) return new AddIngredientsResponse(List.of());
        var master = ingredients.findAllByIdIn(newIds).stream()
                .collect(Collectors.toMap(i -> i.getId(), Function.identity()));
        // 일부만 저장되지 않도록 모든 후보를 검증한 뒤 등록한다.
        if (master.size() != newIds.size() || master.values().stream().anyMatch(i -> !i.isActive())) {
            throw new BusinessException(UserIngredientErrorCode.USER_INGREDIENT_INVALID);
        }
        var added = new ArrayList<AddIngredientsResponse.Item>();
        for (Long id : newIds) {
            if (owned.insertIfAbsent(userId, id)) added.add(AddIngredientsResponse.Item.from(master.get(id), iconBaseUrl));
        }
        return new AddIngredientsResponse(added);
    }
}
