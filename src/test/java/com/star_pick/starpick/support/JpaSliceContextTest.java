package com.star_pick.starpick.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.star_pick.starpick.domain.recipe.repository.RecipeRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

/**
 * CLAUDE.md §11 이 안내하는 JPA 슬라이스 조합이 실제로 기동하는지 지킨다.
 *
 * <p>슬라이스는 {@code @Service} 를 걸러내므로 {@code TestcontainersConfiguration} 에 Service 를
 * 주입받는 Bean 을 하나만 넣어도 이 조합이 {@code UnsatisfiedDependencyException} 으로 죽는다.
 * 실제로 픽스처 Bean 을 거기 뒀다가 이 테스트로 깨지는 것을 확인하고 설정을 분리했다.
 *
 * <p>슬라이스 테스트가 아직 하나도 없어서 깨져도 아무도 모른다. 그래서 이 테스트가 있다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
class JpaSliceContextTest {

    @Autowired
    private RecipeRepository recipeRepository;

    @Test
    void contextLoads() {
        assertThat(recipeRepository.count()).isZero();
    }
}
