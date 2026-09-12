package com.star_pick.starpick.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.star_pick.starpick.domain.ingestion.domain.IngestionJob;
import com.star_pick.starpick.domain.ingestion.domain.RecipeDraft;
import com.star_pick.starpick.domain.ingestion.repository.IngestionJobRepository;
import com.star_pick.starpick.domain.recipe.domain.RecipeCategory;
import jakarta.persistence.EntityManagerFactory;
import java.util.List;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.type.format.jackson.Jackson3JsonFormatMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@IntegrationTest
class JsonFormatMapperTest {

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private IngestionJobRepository repository;

    @Autowired
    private TestFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures.reset();
    }

    @Test
    @DisplayName("Hibernate JSONB 직렬화는 Jackson 3 매퍼를 사용한다")
    void hibernateUsesJackson3() {
        SessionFactoryImplementor sessionFactory = entityManagerFactory.unwrap(SessionFactoryImplementor.class);

        assertThat(sessionFactory.getSessionFactoryOptions().getJsonFormatMapper())
                .isInstanceOf(Jackson3JsonFormatMapper.class);
    }

    @Test
    @DisplayName("RecipeDraft JSONB는 중첩 배열과 null을 포함해 왕복 저장된다")
    void recipeDraftRoundTrips() {
        fixtures.seedUser(7L);
        IngestionJob job = IngestionJob.queueImage(7L, List.of("ingestion-inputs/7/a.jpg"));
        job.startProcessing(java.time.Instant.now());
        RecipeDraft expected = new RecipeDraft(
                "감자전", RecipeCategory.KOREAN, null, 2,
                List.of(new RecipeDraft.Ingredient(31L, "감자", "2개")),
                List.of(new RecipeDraft.Step("감자를 간다.")));
        job.completeWithResult(expected, java.time.Instant.now().plusSeconds(3600));
        Long id = repository.saveAndFlush(job).getId();

        entityManagerFactory.createEntityManager().close();
        RecipeDraft actual = repository.findById(id).orElseThrow().getResult();

        assertThat(actual).isEqualTo(expected);
    }
}
