package com.star_pick.starpick.domain.recipe.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.star_pick.starpick.domain.recipe.domain.Recipe;
import com.star_pick.starpick.domain.recipe.domain.RecipeCategory;
import com.star_pick.starpick.domain.recipe.domain.RecipeIngredient;
import com.star_pick.starpick.domain.recipe.repository.RecipeRepository;
import com.star_pick.starpick.domain.user.repository.UserIngredientRepository;
import com.star_pick.starpick.global.security.jwt.JwtProvider;
import com.star_pick.starpick.support.FakeObjectStorage;
import com.star_pick.starpick.support.IntegrationTest;
import com.star_pick.starpick.support.TestFixtures;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
class RecipeRecommendationApiTest {

    private static final long OWNER_ID = 1L;
    private static final long OTHER_ID = 999L;
    private static final String PATH = "/api/v1/recipes/recommendations";

    @Autowired MockMvc mvc;
    @Autowired JwtProvider jwt;
    @Autowired TestFixtures fixtures;
    @Autowired RecipeRepository recipes;
    @Autowired UserIngredientRepository pantry;
    @Autowired JdbcTemplate jdbc;
    @Autowired FakeObjectStorage storage;

    private String token;
    private Long ingredientId;
    private Long otherIngredientId;

    @BeforeEach
    void setUp() {
        fixtures.reset();
        fixtures.seedUser(OWNER_ID);
        fixtures.seedUser(OTHER_ID);
        token = jwt.generateTokens(OWNER_ID).accessToken();
        List<Long> ids = jdbc.queryForList("select id from ingredient order by id limit 2", Long.class);
        ingredientId = ids.get(0);
        otherIngredientId = ids.get(1);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("update users set deleted_at = null where user_id = ?", OWNER_ID);
        fixtures.restoreIngredientActivity();
        fixtures.reset();
    }

    private ResultActions recommend(String body) throws Exception {
        return mvc.perform(post(PATH)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private Long save(long userId, RecipeIngredient... ingredients) {
        Recipe recipe = Recipe.createManual(userId, "추천 요리", RecipeCategory.KOREAN, 10, 1, null);
        recipe.replaceIngredients(List.of(ingredients));
        return recipes.save(recipe).getId();
    }

    @Test
    void randomReturnsOwnRecipeWithoutPantryOrIngredients() throws Exception {
        Long id = save(OWNER_ID);
        save(OTHER_ID);

        recommend(""" 
                {"recommendationMode":"RANDOM"}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.message").value("레시피 추천에 성공했습니다."))
                .andExpect(jsonPath("$.data.recipeId").value(id))
                .andExpect(jsonPath("$.data.mainIngredients").isEmpty())
                .andExpect(jsonPath("$.data.thumbnailUrl").value(nullValue()));
    }

    @Test
    void returnsExactCardContractAndOrderedNames() throws Exception {
        Long id = save(OWNER_ID, RecipeIngredient.of(null, "두부", "1모"),
                RecipeIngredient.of(null, "대파", null));
        String key = fixtures.attachCover(OWNER_ID, id);

        String body = recommend("{\"recommendationMode\":\"RANDOM\"}")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Map<String, Object> card = JsonPath.parse(body).read("$.data");
        assertThat(card).containsOnlyKeys("recipeId", "title", "category", "thumbnailUrl", "mainIngredients")
                .containsEntry("title", "추천 요리")
                .containsEntry("category", "KOREAN")
                .containsEntry("thumbnailUrl", FakeObjectStorage.VIEW_URL_PREFIX + key)
                .containsEntry("mainIngredients", List.of("두부", "대파"));
    }

    @Test
    void usesAnyOwnedIngredientIdNotRecipeNameOrOtherUsersPantry() throws Exception {
        pantry.insertIfAbsent(OWNER_ID, ingredientId);
        pantry.insertIfAbsent(OTHER_ID, otherIngredientId);
        Long match = save(OWNER_ID, RecipeIngredient.of(ingredientId, "이름을 바꾼 재료", null),
                RecipeIngredient.of(otherIngredientId, "없는 재료", null));
        save(OWNER_ID, RecipeIngredient.of(otherIngredientId, "이름을 바꾼 재료", null));
        save(OTHER_ID, RecipeIngredient.of(ingredientId, "남의 레시피", null));

        recommend("{\"recommendationMode\":\"INGREDIENT_BASED\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recipeId").value(match))
                .andExpect(jsonPath("$.data.mainIngredients.length()").value(2));
    }

    @Test
    void usesAllPantryEntriesAndDoesNotReturnMultipleCardsForMultipleMatches() throws Exception {
        pantry.insertIfAbsent(OWNER_ID, ingredientId);
        pantry.insertIfAbsent(OWNER_ID, otherIngredientId);
        Long previous = save(OWNER_ID, RecipeIngredient.of(ingredientId, "첫 재료", null));
        Long next = save(OWNER_ID, RecipeIngredient.of(otherIngredientId, "두 번째 재료", null),
                RecipeIngredient.of(otherIngredientId, "두 번째 재료 중복", null));

        recommend("""
                {"recommendationMode":"INGREDIENT_BASED","previousRecipeId":%d}
                """.formatted(previous))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recipeId").value(next));
    }

    @Test
    void noPantryDoesNotFallBackToRandomOrOtherUsersPantry() throws Exception {
        pantry.insertIfAbsent(OTHER_ID, ingredientId);
        save(OWNER_ID, RecipeIngredient.of(ingredientId, "재료", null));
        assertEmpty("INGREDIENT_BASED", null);
    }

    @Test
    void noMatchingIngredientDoesNotFallBackToRandom() throws Exception {
        pantry.insertIfAbsent(OWNER_ID, ingredientId);
        save(OWNER_ID, RecipeIngredient.of(otherIngredientId, "다른 재료", null));
        assertEmpty("INGREDIENT_BASED", null);
    }

    @Test
    void unlinkedIngredientIsNotMatchedByName() throws Exception {
        pantry.insertIfAbsent(OWNER_ID, ingredientId);
        String name = jdbc.queryForObject("select name from ingredient where id = ?", String.class, ingredientId);
        save(OWNER_ID, RecipeIngredient.of(null, name, null));
        assertEmpty("INGREDIENT_BASED", null);
    }

    @Test
    void previouslyOwnedInactiveIngredientStillMatchesLikePantryList() throws Exception {
        pantry.insertIfAbsent(OWNER_ID, ingredientId);
        Long id = save(OWNER_ID, RecipeIngredient.of(ingredientId, "보유 재료", null));
        jdbc.update("update ingredient set active = false where id = ?", ingredientId);

        recommend("{\"recommendationMode\":\"INGREDIENT_BASED\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.recipeId").value(id));
    }

    @ParameterizedTest
    @ValueSource(strings = {"RANDOM", "INGREDIENT_BASED"})
    void returnsNullWhenOnlyOtherUsersHaveRecipes(String mode) throws Exception {
        pantry.insertIfAbsent(OWNER_ID, ingredientId);
        save(OTHER_ID, RecipeIngredient.of(ingredientId, "재료", null));
        assertEmpty(mode, null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"RANDOM", "INGREDIENT_BASED"})
    void excludesPreviousEvenIfItIsTheOnlyCandidate(String mode) throws Exception {
        pantry.insertIfAbsent(OWNER_ID, ingredientId);
        Long previous = save(OWNER_ID, RecipeIngredient.of(ingredientId, "재료", null));
        assertEmpty(mode, previous);
    }

    @ParameterizedTest
    @ValueSource(strings = {"RANDOM", "INGREDIENT_BASED"})
    void rerollsWithoutPreviousAndDoesNotChangeRecipeOrSlots(String mode) throws Exception {
        pantry.insertIfAbsent(OWNER_ID, ingredientId);
        Long previous = save(OWNER_ID, RecipeIngredient.of(ingredientId, "재료", null));
        Long next = save(OWNER_ID, RecipeIngredient.of(ingredientId, "재료", null));
        Map<String, Object> before = jdbc.queryForMap(
                "select recipe_slot_limit, cumulative_recipe_count from users where user_id = ?", OWNER_ID);

        for (int i = 0; i < 5; i++) {
            recommend("{\"recommendationMode\":\"%s\",\"previousRecipeId\":%d}".formatted(mode, previous))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.recipeId").value(next));
        }
        assertThat(recipes.count()).isEqualTo(2);
        assertThat(jdbc.queryForMap(
                "select recipe_slot_limit, cumulative_recipe_count from users where user_id = ?", OWNER_ID))
                .isEqualTo(before);
    }

    @Test
    void arbitraryPreviousIdDoesNotExposeOtherUsersRecipe() throws Exception {
        Long own = save(OWNER_ID);
        Long other = save(OTHER_ID);
        for (long previous : List.of(other, Long.MAX_VALUE)) {
            recommend("{\"recommendationMode\":\"RANDOM\",\"previousRecipeId\":%d}".formatted(previous))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.recipeId").value(own));
        }
    }

    @Test
    void prefersCoverThenFallsBackToSourceThumbnail() throws Exception {
        Recipe youtube = fixtures.saveUrlRecipe(OWNER_ID, "https://www.youtube.com/watch?v=kjG6h_LTklo");
        recommend("{\"recommendationMode\":\"RANDOM\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.thumbnailUrl").value("https://i.ytimg.com/vi/kjG6h_LTklo/hqdefault.jpg"));
        String cover = fixtures.attachCover(OWNER_ID, youtube.getId());
        recommend("{\"recommendationMode\":\"RANDOM\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.thumbnailUrl").value(FakeObjectStorage.VIEW_URL_PREFIX + cover));
    }

    @Test
    void sourceImageSigningFailureDoesNotFailRecommendation() throws Exception {
        fixtures.saveInstagramRecipe(OWNER_ID);
        storage.startFailing();
        recommend("{\"recommendationMode\":\"RANDOM\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.thumbnailUrl").value(nullValue()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"recommendationMode\":null}",
            "{\"recommendationMode\":\"RANDOM\",\"previousRecipeId\":0}",
            "{\"recommendationMode\":\"RANDOM\",\"previousRecipeId\":-1}"})
    void rejectsInvalidValues(String body) throws Exception {
        recommend(body).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.code").value("REQUEST_VALIDATION_FAILED"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "{", "{\"recommendationMode\":\"UNKNOWN\"}",
            "{\"recommendationMode\":\"RANDOM\",\"previousRecipeId\":\"abc\"}"})
    void rejectsInvalidRequestFormat(String body) throws Exception {
        recommend(body).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.code").value("INVALID_REQUEST_FORMAT"));
    }

    @Test
    void requiresAuthentication() throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recommendationMode\":\"RANDOM\"}"))
                .andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"RANDOM", "INGREDIENT_BASED"})
    void rejectsDeletedAccount(String mode) throws Exception {
        jdbc.update("update users set deleted_at = now() where user_id = ?", OWNER_ID);
        recommend("{\"recommendationMode\":\"%s\"}".formatted(mode))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.data.code").value("AUTHENTICATION_REQUIRED"));
    }

    private void assertEmpty(String mode, Long previous) throws Exception {
        String body = recommend("{\"recommendationMode\":\"%s\",\"previousRecipeId\":%s}".formatted(mode, previous))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("조건에 맞는 추천 레시피가 없습니다."))
                .andReturn().getResponse().getContentAsString();
        Map<String, Object> response = JsonPath.parse(body).read("$");
        assertThat(response).containsEntry("data", null);
    }
}
