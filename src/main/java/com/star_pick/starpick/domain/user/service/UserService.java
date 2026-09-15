package com.star_pick.starpick.domain.user.service;

import com.star_pick.starpick.domain.ingredient.repository.IngredientRepository;
import com.star_pick.starpick.domain.user.dto.*;
import com.star_pick.starpick.domain.user.entity.Profile;
import com.star_pick.starpick.domain.user.entity.User;
import com.star_pick.starpick.domain.ingredient.domain.Ingredient;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.Locale;
import com.star_pick.starpick.domain.user.exception.UserIngredientErrorCode;
import com.star_pick.starpick.domain.user.repository.ProfileRepository;
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
public class UserService {
    private final UserRepository users;
    private final IngredientRepository ingredients;
    private final UserIngredientRepository owned;
    private final String iconBaseUrl;
    private final ProfileRepository profiles;

    public UserService(UserRepository users, IngredientRepository ingredients,
            UserIngredientRepository owned, ProfileRepository profiles, @Value("${starpick.ingredient.icon-base-url}") String iconBaseUrl) {
        this.users = users;
        this.ingredients = ingredients;
        this.owned = owned;
        this.profiles = profiles;
        this.iconBaseUrl = iconBaseUrl.replaceAll("/+$", "");
    }

    @Transactional
    public AddIngredientsResponse addIngredients(Long userId, List<Long> ingredientIds) {
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
        var added = new ArrayList<UserIngredientResponse>();
        for (Long id : newIds) {
            if (owned.insertIfAbsent(userId, id)) added.add(UserIngredientResponse.from(master.get(id), iconBaseUrl));
        }
        return new AddIngredientsResponse(added);
    }

    @Transactional(readOnly = true)
    public UserIngredientsResponse getIngredients(Long userId, String searchQuery) {
        active(users.findById(userId).orElseThrow(this::unauthorized));
        var ids = owned.findIngredientIds(userId);
        if (ids.isEmpty()) return new UserIngredientsResponse(List.of());
        String query = searchQuery == null ? "" : searchQuery.strip().toLowerCase(Locale.ROOT);
        // 보유한 비활성 재료도 유지한다. 검색 기호는 LIKE 패턴이 아니라 일반 문자로 취급한다.
        var result = ingredients.findAllByIdIn(ids).stream()
                .filter(i -> i.getName().toLowerCase(Locale.ROOT).contains(query))
                .sorted(Comparator.comparing(Ingredient::getCategory).thenComparing(Ingredient::getName)
                        .thenComparing(Ingredient::getId))
                .map(i -> UserIngredientResponse.from(i, iconBaseUrl))
                .toList();
        return new UserIngredientsResponse(result);
    }

    @Transactional(readOnly = true)
    public OnboardingResponse getOnboarding(Long userId) {
        return OnboardingResponse.from(active(users.findById(userId).orElseThrow(this::unauthorized)));
    }

    @Transactional
    public OnboardingResponse completeOnboarding(Long userId) {
        User user = active(users.findByIdForUpdate(userId).orElseThrow(this::unauthorized));
        user.completeOnboarding(Instant.now().truncatedTo(ChronoUnit.MICROS));
        return OnboardingResponse.from(user);
    }

    private User active(User user) {
        if (user.getDeletedAt() != null) throw unauthorized();
        return user;
    }

    private BusinessException unauthorized() {
        return new BusinessException(CommonErrorCode.AUTHENTICATION_REQUIRED);
    }

    @Transactional(readOnly = true)
    public MyInfoResponse getMe(Long userId) {
        User user = active(users.findById(userId).orElseThrow(this::unauthorized));
        Profile profile = profiles.findByUser_UserId(userId).orElse(null);
        return MyInfoResponse.from(user, profile);
    }
}
