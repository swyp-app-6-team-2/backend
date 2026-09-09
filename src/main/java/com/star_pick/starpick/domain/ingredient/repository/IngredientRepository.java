package com.star_pick.starpick.domain.ingredient.repository;

import com.star_pick.starpick.domain.ingredient.domain.Ingredient;
import java.util.Collection;
import java.util.List;
import org.springframework.data.repository.Repository;

/**
 * 재료 마스터 조회 전용 Repository.
 *
 * <p><b>{@code JpaRepository} 를 상속하지 않는다.</b> 이 테이블의 데이터는 Flyway migration 이
 * 소유하며 애플리케이션에 쓰기 경로가 없다. {@code JpaRepository} 를 쓰면 {@code save}·
 * {@code deleteAll} 이 함께 열리는데, 테스트가 정리 목적으로 {@code deleteAll} 을 부르면
 * {@code @IntegrationTest} 가 JVM 전체에서 컨테이너 하나를 공유하므로 이후 모든 테스트의
 * 시드가 사라진다. 필요한 두 메서드만 노출해 그 경로를 막는다.
 */
public interface IngredientRepository extends Repository<Ingredient, Long> {

    List<Ingredient> findAllByActiveTrue();

    long countByIdIn(Collection<Long> ingredientIds);
}
