package com.star_pick.starpick.domain.recipe.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.star_pick.starpick.domain.recipe.domain.Recipe;
import com.star_pick.starpick.domain.recipe.domain.RecipeCategory;
import com.star_pick.starpick.domain.recipe.domain.RecipeIngredient;
import com.star_pick.starpick.domain.recipe.repository.RecipeRepository;
import com.star_pick.starpick.global.security.jwt.JwtProvider;
import com.star_pick.starpick.support.IntegrationTest;
import com.star_pick.starpick.support.TestFixtures;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@IntegrationTest
class RecipeSearchApiTest {

    private static final long OWNER = 1L;
    private static final long OTHER = 999L;

    @Autowired MockMvc mvc;
    @Autowired JwtProvider jwt;
    @Autowired TestFixtures fixtures;
    @Autowired RecipeRepository recipes;
    @Autowired JdbcTemplate jdbc;

    private String token;

    @BeforeEach
    void setUp() {
        fixtures.reset();
        fixtures.seedUser(OWNER);
        fixtures.seedUser(OTHER);
        token = jwt.generateTokens(OWNER).accessToken();
    }

    @AfterEach
    void cleanUp() {
        fixtures.reset();
    }

    private Long save(long owner, String title, RecipeCategory category, String... names) {
        Recipe recipe = Recipe.createManual(owner, title, category, 10, 1, null);
        recipe.replaceIngredients(java.util.Arrays.stream(names)
                .map(name -> RecipeIngredient.of(null, name, null)).toList());
        return recipes.save(recipe).getId();
    }

    private ResultActions list(String... params) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/v1/recipes")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        for (int i = 0; i < params.length; i += 2) {
            request.param(params[i], params[i + 1]);
        }
        return mvc.perform(request);
    }

    @Test
    void searchesOnlyOwnedRecipeTitlesNotIngredientNames() throws Exception {
        Long match = save(OWNER, "간단 김치찌개", RecipeCategory.KOREAN, "돼지고기");
        save(OWNER, "볶음밥", RecipeCategory.KOREAN, "김치");
        save(OTHER, "김치찌개", RecipeCategory.KOREAN, "김치");

        list("searchQuery", "김치")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(1))
                .andExpect(jsonPath("$.data.recipes[0].recipeId").value(match));
    }

    @Test
    void trimsSearchAndIgnoresEnglishCase() throws Exception {
        Long id = save(OWNER, "Chicken SALAD", RecipeCategory.WESTERN);
        list("searchQuery", "  salad  ")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(1))
                .andExpect(jsonPath("$.data.recipes[0].recipeId").value(id));
    }

    @ParameterizedTest
    @ValueSource(strings = {"%", "_", "!", "\\", "'"})
    void treatsSearchSymbolsAsLiteralCharacters(String symbol) throws Exception {
        Long id = save(OWNER, "기호" + symbol + "요리", RecipeCategory.OTHER);
        save(OWNER, "기호다른요리", RecipeCategory.OTHER);
        list("searchQuery", symbol)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(1))
                .andExpect(jsonPath("$.data.recipes[0].recipeId").value(id));
    }

    @Test
    void blankSearchKeepsExistingListBehavior() throws Exception {
        save(OWNER, "밥", RecipeCategory.KOREAN);
        save(OWNER, "면", RecipeCategory.CHINESE);
        list("searchQuery", "   ")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalCount").value(2));
    }

    @Test
    void returnsEmptyRecipesAndZeroCountWhenNoMatch() throws Exception {
        save(OWNER, "밥", RecipeCategory.KOREAN);
        list("searchQuery", "없는 요리")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(0))
                .andExpect(jsonPath("$.data.recipes").isEmpty());
    }

    @Test
    void categoryFilterIncludesAnySelectedCategory() throws Exception {
        save(OWNER, "한식", RecipeCategory.KOREAN);
        save(OWNER, "중식", RecipeCategory.CHINESE);
        save(OWNER, "양식", RecipeCategory.WESTERN);
        save(OTHER, "남의 한식", RecipeCategory.KOREAN);
        list("category", "KOREAN", "category", "CHINESE")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(2));
    }

    @Test
    void ingredientFilterRequiresAllSelectedNamesWithoutPantry() throws Exception {
        Long match = save(OWNER, "두부찌개", RecipeCategory.KOREAN, "두부", "대파");
        save(OWNER, "두부구이", RecipeCategory.KOREAN, "두부");
        save(OWNER, "파전", RecipeCategory.KOREAN, "대파");
        save(OTHER, "남의 찌개", RecipeCategory.KOREAN, "두부", "대파");

        list("ingredientName", "두부", "ingredientName", "대파")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(1))
                .andExpect(jsonPath("$.data.recipes[0].recipeId").value(match))
                .andExpect(jsonPath("$.data.recipes[0].ingredientNames[0]").value("두부"))
                .andExpect(jsonPath("$.data.recipes[0].ingredientNames[1]").value("대파"));
    }

    @Test
    void matchesWholeIngredientNameRatherThanSubstringOrTitle() throws Exception {
        Long match = save(OWNER, "찌개", RecipeCategory.KOREAN, "파");
        save(OWNER, "다른 찌개", RecipeCategory.KOREAN, "대파");
        save(OWNER, "파", RecipeCategory.KOREAN, "두부");
        list("ingredientName", "파")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(1))
                .andExpect(jsonPath("$.data.recipes[0].recipeId").value(match));
    }

    @Test
    void combinesTitleCategoryAndAllIngredients() throws Exception {
        Long match = save(OWNER, "매운 찌개", RecipeCategory.KOREAN, "두부", "대파", "김치");
        save(OWNER, "매운 찌개", RecipeCategory.WESTERN, "두부", "대파");
        save(OWNER, "매운 찌개", RecipeCategory.CHINESE, "두부");
        save(OWNER, "전골", RecipeCategory.KOREAN, "두부", "대파");
        save(OTHER, "매운 찌개", RecipeCategory.KOREAN, "두부", "대파");

        list("searchQuery", "찌개", "category", "KOREAN", "category", "CHINESE",
                "ingredientName", "두부", "ingredientName", "대파")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(1))
                .andExpect(jsonPath("$.data.recipes[0].recipeId").value(match))
                // 필터에 쓰지 않은 재료도 카드에는 원래대로 표시한다.
                .andExpect(jsonPath("$.data.recipes[0].ingredientNames.length()").value(3));
    }

    @Test
    void duplicateRowsCannotSubstituteForMissingSelectedIngredient() throws Exception {
        save(OWNER, "부족", RecipeCategory.KOREAN, "두부", "두부");
        Long match = save(OWNER, "충분", RecipeCategory.KOREAN, "두부", "두부", "대파");
        list("ingredientName", "두부", "ingredientName", "대파", "size", "1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(1))
                .andExpect(jsonPath("$.data.recipes.length()").value(1))
                .andExpect(jsonPath("$.data.recipes[0].recipeId").value(match));
    }

    @Test
    void normalizesAndDeduplicatesFilterValues() throws Exception {
        Long match = save(OWNER, "샐러드", RecipeCategory.WESTERN, " SALT ", "Pepper");
        list("category", "WESTERN", "category", "WESTERN",
                "ingredientName", " salt ", "ingredientName", "SALT", "ingredientName", "pepper")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(1))
                .andExpect(jsonPath("$.data.recipes[0].recipeId").value(match));
    }

    @Test
    void blankFiltersClearSelections() throws Exception {
        save(OWNER, "한식", RecipeCategory.KOREAN);
        save(OWNER, "중식", RecipeCategory.CHINESE);
        list("category", "", "ingredientName", "  ", "searchQuery", "")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalCount").value(2));
    }

    @Test
    void unknownIngredientReturnsEmptyList() throws Exception {
        save(OWNER, "찌개", RecipeCategory.KOREAN, "두부");
        list("ingredientName", "미등록재료")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(0))
                .andExpect(jsonPath("$.data.recipes").isEmpty());
    }

    @Test
    void ingredientFilteringPreservesCountAndPaginationWithDuplicateRows() throws Exception {
        for (int i = 0; i < 3; i++) {
            save(OWNER, "찌개 " + i, RecipeCategory.KOREAN, "두부", "두부", "대파");
        }
        save(OWNER, "부족", RecipeCategory.KOREAN, "두부", "두부");
        List<Number> ids = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            String body = list("ingredientName", "두부", "ingredientName", "대파",
                    "size", "1", "page", String.valueOf(page))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalCount").value(3))
                    .andExpect(jsonPath("$.data.recipes.length()").value(1))
                    .andReturn().getResponse().getContentAsString();
            ids.add(JsonPath.parse(body).read("$.data.recipes[0].recipeId"));
        }
        assertThat(ids).doesNotHaveDuplicates();
    }

    @ParameterizedTest
    @ValueSource(strings = {"category", "sort"})
    void rejectsUnknownEnumValues(String parameter) throws Exception {
        list(parameter, "UNKNOWN").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.code").value("INVALID_REQUEST_FORMAT"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"searchQuery", "ingredientName"})
    void rejectsExcessiveText(String parameter) throws Exception {
        list(parameter, "가".repeat(256)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.code").value("REQUEST_VALIDATION_FAILED"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"category", "ingredientName"})
    void rejectsExcessiveFilterValues(String parameter) throws Exception {
        String[] values = java.util.Collections.nCopies(101,
                parameter.equals("category") ? "KOREAN" : "두부").toArray(String[]::new);
        mvc.perform(get("/api/v1/recipes").param(parameter, values)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.code").value("REQUEST_VALIDATION_FAILED"));
    }

    @Test
    void filteredRequestRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/v1/recipes").param("searchQuery", "찌개").param("category", "KOREAN"))
                .andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"LATEST", "OLDEST"})
    void paginatesFilteredResultsWithStableOrderingAndFilteredTotal(String sort) throws Exception {
        List<Long> expected = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Long id = save(OWNER, "검색 요리 " + i, RecipeCategory.KOREAN);
            jdbc.update("update recipe set created_at = '2026-09-01T00:00:00Z' where id = ?", id);
            expected.add(id);
        }
        save(OWNER, "다른 요리", RecipeCategory.KOREAN);
        save(OTHER, "검색 요리", RecipeCategory.KOREAN);
        if (sort.equals("LATEST")) {
            expected = expected.reversed();
        }

        List<Long> actual = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            String body = list("searchQuery", "검색", "sort", sort, "page", String.valueOf(page), "size", "2")
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalCount").value(5))
                    .andReturn().getResponse().getContentAsString();
            List<Number> ids = JsonPath.parse(body).read("$.data.recipes[*].recipeId");
            ids.forEach(id -> actual.add(id.longValue()));
        }
        assertThat(actual).containsExactlyElementsOf(expected);
        list("searchQuery", "검색", "page", "3", "size", "2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(5))
                .andExpect(jsonPath("$.data.recipes").isEmpty());
    }
}
