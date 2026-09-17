package com.star_pick.starpick.domain.user.service;

import com.star_pick.starpick.domain.ingredient.repository.IngredientRepository;
import com.star_pick.starpick.domain.user.dto.*;
import com.star_pick.starpick.domain.user.entity.Profile;
import com.star_pick.starpick.domain.user.entity.User;
import com.star_pick.starpick.domain.ingredient.domain.Ingredient;
import java.time.Instant;
import com.star_pick.starpick.domain.user.repository.ProfileRepository;
import com.star_pick.starpick.domain.user.dto.ProfileUpdateRequest;
import com.star_pick.starpick.domain.user.dto.ProfileResponse;
import com.star_pick.starpick.domain.user.exception.ProfileErrorCode;
import com.star_pick.starpick.domain.upload.service.UploadService;
import com.star_pick.starpick.domain.upload.service.AttachOutcome;
import com.star_pick.starpick.domain.upload.domain.UploadPurpose;
import java.util.Objects;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.Locale;
import com.star_pick.starpick.domain.user.entity.UserCustomIngredient;
import com.star_pick.starpick.domain.user.exception.UserIngredientErrorCode;
import com.star_pick.starpick.domain.user.repository.UserCustomIngredientRepository;
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
    private final ProfileRepository profiles;
    private final UploadService uploads;
    private final UserRepository users;
    private final IngredientRepository ingredients;
    private final UserIngredientRepository owned;
    private final UserCustomIngredientRepository customIngredients;
    private final UserLifecycleGuard lifecycle;
    private final String iconBaseUrl;

    public UserService(ProfileRepository profiles, UploadService uploads, UserRepository users, IngredientRepository ingredients,
            UserIngredientRepository owned, UserCustomIngredientRepository customIngredients, UserLifecycleGuard lifecycle,
            @Value("${starpick.ingredient.icon-base-url}") String iconBaseUrl) {
        this.uploads = uploads;
        this.users = users;
        this.ingredients = ingredients;
        this.owned = owned;
        this.customIngredients = customIngredients;
        this.lifecycle = lifecycle;
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
    public void requireActiveUser(Long userId) {
        active(users.findById(userId).orElseThrow(this::unauthorized));
    }

    /** 추천 등 다른 도메인에는 사용자 엔티티 대신 보유 재료 ID만 제공한다. */
    @Transactional(readOnly = true)
    public List<Long> getOwnedIngredientIds(Long userId) {
        requireActiveUser(userId);
        return owned.findIngredientIds(userId);
    }

    /** 추천 도메인에 커스텀 재료 이름만 제공한다. recipe_ingredient.name 과 트림 후 완전 일치로 매칭한다. */
    @Transactional(readOnly = true)
    public List<String> getOwnedCustomIngredientNames(Long userId) {
        requireActiveUser(userId);
        return customIngredients.findByUserId(userId).stream().map(UserCustomIngredient::getName).toList();
    }

    @Transactional
    public UserIngredientResponse addCustomIngredient(Long userId, String name) {
        lifecycle.lockActive(userId);
        var saved = customIngredients.save(UserCustomIngredient.create(userId, name));
        return UserIngredientResponse.fromCustom(saved);
    }

    @Transactional
    public DeleteIngredientsResponse deleteIngredients(Long userId, DeleteIngredientsRequest request) {
        lifecycle.lockActive(userId);
        validateDeleteRequest(request);

        int deletedCount;
        if (request.mode() == IngredientDeleteMode.ALL) {
            deletedCount = owned.deleteAllForUser(userId) + customIngredients.deleteAllByUserId(userId);
        } else {
            var masterIds = request.ingredients().stream()
                    .filter(item -> item.type() == IngredientType.MASTER)
                    .map(DeleteIngredientItem::id)
                    .distinct()
                    .toList();
            var customIds = request.ingredients().stream()
                    .filter(item -> item.type() == IngredientType.CUSTOM)
                    .map(DeleteIngredientItem::id)
                    .distinct()
                    .toList();
            deletedCount = masterIds.stream().mapToInt(id -> owned.delete(userId, id)).sum();
            if (!customIds.isEmpty()) {
                deletedCount += customIngredients.deleteByUserIdAndIdIn(userId, customIds);
            }
        }
        return new DeleteIngredientsResponse(deletedCount);
    }

    private void validateDeleteRequest(DeleteIngredientsRequest request) {
        if (request == null || request.mode() == null || request.ingredients() == null
                || (request.mode() == IngredientDeleteMode.SELECTED && request.ingredients().isEmpty())
                || (request.mode() == IngredientDeleteMode.ALL && !request.ingredients().isEmpty())) {
            throw new BusinessException(CommonErrorCode.REQUEST_VALIDATION_FAILED);
        }
    }

    @Transactional(readOnly = true)
    public UserIngredientsResponse getIngredients(Long userId, String searchQuery) {
        active(users.findById(userId).orElseThrow(this::unauthorized));
        String query = searchQuery == null ? "" : searchQuery.strip().toLowerCase(Locale.ROOT);
        var ids = owned.findIngredientIds(userId);
        // 보유한 비활성 재료도 유지한다. 검색 기호는 LIKE 패턴이 아니라 일반 문자로 취급한다.
        var master = ids.isEmpty() ? List.<UserIngredientResponse>of() : ingredients.findAllByIdIn(ids).stream()
                .filter(i -> i.getName().toLowerCase(Locale.ROOT).contains(query))
                .sorted(Comparator.comparing(Ingredient::getCategory).thenComparing(Ingredient::getName)
                        .thenComparing(Ingredient::getId))
                .map(i -> UserIngredientResponse.from(i, iconBaseUrl))
                .toList();
        // 커스텀 재료는 기존 재료 뒤에 이름순·ID순으로 배치한다.
        var custom = customIngredients.findByUserId(userId).stream()
                .filter(c -> c.getName().toLowerCase(Locale.ROOT).contains(query))
                .sorted(Comparator.comparing(UserCustomIngredient::getName).thenComparing(UserCustomIngredient::getId))
                .map(UserIngredientResponse::fromCustom)
                .toList();
        var result = new ArrayList<UserIngredientResponse>(master.size() + custom.size());
        result.addAll(master);
        result.addAll(custom);
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
        String url = profile == null ? null : profile.getProfileImageKey() == null
                ? profile.getProfileImageUrl() : uploads.getViewUrl(userId, profile.getProfileImageKey());
        return new MyInfoResponse(userId, profile == null ? null : profile.getNickname(), url,
                user.getRemainingRecipeSlots(), user.getRecipeSlotLimit(), user.getCumulativeRecipeCount());
    }

    @Transactional
    public ProfileResponse updateProfile(Long userId, ProfileUpdateRequest request) {
        var user = active(users.findByIdForUpdate(userId).orElseThrow(this::unauthorized));
        var profile = profiles.findByUser_UserId(userId).orElseGet(() -> profiles.save(Profile.initial(user)));
        if (request.getProfileImageKey() != null) {
            String next = request.getProfileImageKey().orElse(null);
            String previous = profile.getProfileImageKey();
            if (!Objects.equals(previous, next)) {
                if (next != null && uploads.attach(userId, next, UploadPurpose.PROFILE_IMAGE) != AttachOutcome.ATTACHED) {
                    throw new BusinessException(ProfileErrorCode.PROFILE_IMAGE_INVALID);
                }
                uploads.releaseAndDeleteFile(userId, previous, UploadPurpose.PROFILE_IMAGE);
            }
            profile.changeImage(next);
        }
        profile.changeNickname(request.getNickname());
        String url = profile.getProfileImageKey() == null ? profile.getProfileImageUrl()
                : uploads.getViewUrl(userId, profile.getProfileImageKey());
        return new ProfileResponse(userId, profile.getNickname(), url);
    }
}
