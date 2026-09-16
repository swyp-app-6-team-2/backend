package com.star_pick.starpick.domain.recipe.repository;

import com.star_pick.starpick.domain.recipe.domain.Recipe;
import com.star_pick.starpick.domain.recipe.domain.RecipeCategory;
import com.star_pick.starpick.domain.recipe.domain.RecipeIngredient;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;

/** 목록과 count에 같은 조건을 적용하며, 모든 검색에 소유권 조건을 포함한다. */
public final class RecipeSearchSpecifications {

    private RecipeSearchSpecifications() {
    }

    public static Specification<Recipe> matching(Long userId, String searchQuery,
            List<RecipeCategory> categories, List<String> ingredientNames) {
        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(builder.equal(root.get("userId"), userId));
            if (searchQuery != null) {
                // 사용자 입력의 %, _, !를 SQL 패턴으로 해석하지 않는다.
                String escaped = searchQuery.replace("!", "!!").replace("%", "!%").replace("_", "!_");
                predicates.add(builder.like(builder.lower(root.get("title")), "%" + escaped + "%", '!'));
            }
            if (!categories.isEmpty()) {
                predicates.add(root.get("categoryCode").in(categories));
            }
            if (!ingredientNames.isEmpty()) {
                // 중복 재료 행이 있어도 선택한 서로 다른 이름을 모두 포함해야 한다.
                // 컬렉션 join으로 부모 행을 늘리지 않아 페이지 및 totalCount도 정확하다.
                Subquery<Long> matchedCount = query.subquery(Long.class);
                Root<RecipeIngredient> ingredient = matchedCount.from(RecipeIngredient.class);
                var normalizedName = builder.lower(builder.trim(ingredient.<String>get("name")));
                matchedCount.select(builder.countDistinct(normalizedName))
                        .where(builder.equal(ingredient.get("recipe"), root),
                                normalizedName.in(ingredientNames));
                predicates.add(builder.equal(matchedCount, (long) ingredientNames.size()));
            }
            return builder.and(predicates.toArray(Predicate[]::new));
        };
    }
}
