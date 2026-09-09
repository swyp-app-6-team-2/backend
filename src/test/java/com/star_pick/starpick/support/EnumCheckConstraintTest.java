package com.star_pick.starpick.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.star_pick.starpick.domain.ingredient.domain.IngredientCategory;
import com.star_pick.starpick.domain.recipe.domain.RecipeCategory;
import com.star_pick.starpick.domain.recipe.domain.RegistrationMethod;
import com.star_pick.starpick.domain.upload.domain.UploadPurpose;
import com.star_pick.starpick.domain.user.entity.Provider;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * enum 상수와 migration 의 CHECK 제약이 같은 값 집합인지 확인한다.
 *
 * <p>migration 이 enum 값을 SQL 에 고정하는데 {@code ddl-auto: validate} 는 CHECK 를 보지 않는다.
 * 이 테스트가 없으면 enum 에 상수를 추가해도 전부 통과하고 운영 첫 요청이 500 으로 죽는다.
 * 깨졌다면 고칠 곳은 이 테스트가 아니라 새 migration 이다.
 */
@IntegrationTest
class EnumCheckConstraintTest {

    /** PostgreSQL 은 CHECK 를 {@code 'KOREAN'::character varying} 형태로 돌려준다. */
    private static final Pattern SQL_LITERAL = Pattern.compile("'([^']*)'");

    private record Constraint(String name, Class<? extends Enum<?>> enumType) { }

    private static final List<Constraint> CONSTRAINTS = List.of(
            new Constraint("ck_ingredient_category_code", IngredientCategory.class),
            new Constraint("ck_recipe_category_code", RecipeCategory.class),
            new Constraint("ck_recipe_registration_method", RegistrationMethod.class),
            new Constraint("ck_upload_object_purpose", UploadPurpose.class),
            new Constraint("ck_users_last_login_provider", Provider.class),
            new Constraint("ck_social_credentials_provider", Provider.class));

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("CHECK 제약의 값 집합이 enum 상수와 정확히 같다")
    void checkConstraintsMatchEnums() {
        for (Constraint constraint : CONSTRAINTS) {
            String definition = jdbcTemplate.queryForObject("""
                    select pg_get_constraintdef(oid) from pg_constraint where conname = ?
                    """, String.class, constraint.name());

            assertThat(definition)
                    .as("%s 제약이 없다 — migration 에서 빠졌는지 확인한다", constraint.name())
                    .isNotNull();

            assertThat(literalsIn(definition))
                    .as("%s 의 값 집합이 %s 상수와 다르다. 새 migration 으로 제약을 갱신한다",
                            constraint.name(), constraint.enumType().getSimpleName())
                    .containsExactlyInAnyOrderElementsOf(constantsOf(constraint.enumType()));
        }
    }

    @Test
    @DisplayName("migration 의 모든 ck_ 제약이 위 목록에 등록돼 있다")
    void everyCheckConstraintIsRegistered() {
        // 위 테스트만 있으면 목록에 등록하지 않은 제약이 조용히 통과한다.
        // PostgreSQL 17+ 는 NOT NULL 을 contype 'n' 으로 따로 두므로 'c' 는 CHECK 뿐이다.
        List<String> inDatabase = jdbcTemplate.queryForList("""
                select conname from pg_constraint
                where contype = 'c'
                  and connamespace = 'public'::regnamespace
                  and conname like 'ck\\_%'
                """, String.class);

        assertThat(inDatabase)
                .as("등록되지 않은 ck_ 제약이 있다. enum 제약이면 CONSTRAINTS 에 추가하고, "
                        + "enum 과 무관한 CHECK 라면 이 쿼리에서 제외한 뒤 그 이유를 적는다")
                .containsExactlyInAnyOrderElementsOf(CONSTRAINTS.stream().map(Constraint::name).toList());
    }

    private List<String> literalsIn(String constraintDefinition) {
        Matcher matcher = SQL_LITERAL.matcher(constraintDefinition);
        return matcher.results().map(result -> result.group(1)).toList();
    }

    private List<String> constantsOf(Class<? extends Enum<?>> enumType) {
        return Arrays.stream(enumType.getEnumConstants()).map(Enum::name).toList();
    }
}
