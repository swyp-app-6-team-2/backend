package com.star_pick.starpick.domain.ingredient;

import static org.assertj.core.api.Assertions.assertThat;

import com.star_pick.starpick.support.IntegrationTest;
import java.io.IOException;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;

@IntegrationTest
class IngredientIconAssetTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("모든 icon_key 에 WebP가 있고 모든 WebP는 icon_key에서 참조된다")
    void iconKeysAndWebpFileNamesMatchBidirectionally() throws IOException {
        Set<String> iconKeys = Set.copyOf(jdbcTemplate.queryForList(
                "select distinct icon_key from ingredient", String.class));
        Resource[] resources = new PathMatchingResourcePatternResolver().getResources(
                "classpath:/static/images/ingredients/*.webp");
        Set<String> assetKeys = Arrays.stream(resources)
                .map(Resource::getFilename)
                .map(fileName -> fileName.substring(0, fileName.length() - ".webp".length()))
                .collect(Collectors.toSet());

        assertThat(assetKeys).hasSize(58);
        assertThat(iconKeys).containsExactlyInAnyOrderElementsOf(assetKeys);
    }
}
