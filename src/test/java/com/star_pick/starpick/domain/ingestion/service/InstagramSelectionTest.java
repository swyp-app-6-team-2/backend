package com.star_pick.starpick.domain.ingestion.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.star_pick.starpick.domain.ingestion.domain.IngestionFailureCode;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class InstagramSelectionTest {

    private static final InstagramMedia IMAGE_1 = new InstagramMedia(false, "i1", null);
    private static final InstagramMedia VIDEO_2 = new InstagramMedia(true, "t2", "v2");
    private static final InstagramMedia IMAGE_3 = new InstagramMedia(false, "i3", null);
    private static final InstagramPost CAROUSEL = new InstagramPost("caption", List.of(IMAGE_1, VIDEO_2, IMAGE_3));

    @Test
    @DisplayName("단일 이미지·단일 영상은 img_index 와 관계없이 그 미디어를 분석한다")
    void singleMediaIgnoresIndex() {
        assertThat(InstagramSelection.of(new InstagramPost(null, List.of(IMAGE_1)), 5))
                .isEqualTo(new InstagramSelection(List.of("i1"), null, "i1", null));
        assertThat(InstagramSelection.of(new InstagramPost(null, List.of(VIDEO_2)), null))
                .isEqualTo(new InstagramSelection(List.of(), "v2", "t2", null));
    }

    @Test
    @DisplayName("img_index 없는 carousel 은 이미지 카드 전부를 순서대로, 미리보기는 첫 이미지 카드")
    void carouselWithoutIndexTakesAllImages() {
        assertThat(InstagramSelection.of(CAROUSEL, null))
                .isEqualTo(new InstagramSelection(List.of("i1", "i3"), null, "i1", null));
    }

    @Test
    @DisplayName("img_index 는 1부터 세고, 영상 카드면 분석 없이 CONTENT_NOT_RECOGNIZED")
    void carouselWithIndex() {
        assertThat(InstagramSelection.of(CAROUSEL, 3))
                .isEqualTo(new InstagramSelection(List.of("i3"), null, "i3", null));
        assertThat(InstagramSelection.of(CAROUSEL, 2))
                .isEqualTo(new InstagramSelection(List.of(), null, "t2", IngestionFailureCode.CONTENT_NOT_RECOGNIZED));
    }

    @Test
    @DisplayName("카드 수를 넘는 img_index 는 SOURCE_UNAVAILABLE, 이미지 카드가 없으면 CONTENT_NOT_RECOGNIZED")
    void failsWithoutAnalyzableCard() {
        assertThat(InstagramSelection.of(CAROUSEL, 4))
                .isEqualTo(new InstagramSelection(List.of(), null, null, IngestionFailureCode.SOURCE_UNAVAILABLE));
        InstagramPost videos = new InstagramPost(null, List.of(VIDEO_2, new InstagramMedia(true, "t4", "v4")));
        assertThat(InstagramSelection.of(videos, null))
                .isEqualTo(new InstagramSelection(List.of(), null, "t2", IngestionFailureCode.CONTENT_NOT_RECOGNIZED));
    }

    @Test
    @DisplayName("분석에 필요한 주소가 없으면 SOURCE_UNAVAILABLE 이고, 고르지 않은 카드의 주소는 보지 않는다")
    void requiresOnlyAddressesItUses() {
        InstagramMedia brokenVideo = new InstagramMedia(true, null, null);
        InstagramMedia brokenImage = new InstagramMedia(false, null, null);

        assertThat(InstagramSelection.of(new InstagramPost(null, List.of(IMAGE_1, brokenVideo)), null))
                .isEqualTo(new InstagramSelection(List.of("i1"), null, "i1", null));
        assertThat(InstagramSelection.of(new InstagramPost(null, List.of(IMAGE_1, brokenImage)), 1))
                .isEqualTo(new InstagramSelection(List.of("i1"), null, "i1", null));
        assertThat(InstagramSelection.of(new InstagramPost(null, List.of(IMAGE_1, brokenImage)), null))
                .isEqualTo(new InstagramSelection(List.of(), null, "i1", IngestionFailureCode.SOURCE_UNAVAILABLE));
        assertThat(InstagramSelection.of(new InstagramPost(null, List.of(new InstagramMedia(true, "t", null))), null))
                .isEqualTo(new InstagramSelection(List.of(), null, "t", IngestionFailureCode.SOURCE_UNAVAILABLE));
        assertThat(InstagramSelection.of(new InstagramPost(null, List.of(brokenImage)), null))
                .isEqualTo(new InstagramSelection(List.of(), null, null, IngestionFailureCode.SOURCE_UNAVAILABLE));
    }
}
