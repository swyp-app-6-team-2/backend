package com.star_pick.starpick.domain.ingestion.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class YouTubeUrlTest {

    @ParameterizedTest
    @CsvSource({
            "https://www.youtube.com/watch?v=kjG6h_LTklo,                https://www.youtube.com/watch?v=kjG6h_LTklo",
            "https://youtube.com/watch?feature=share&v=kjG6h_LTklo&t=30, https://www.youtube.com/watch?v=kjG6h_LTklo",
            "http://m.youtube.com/watch?v=kjG6h_LTklo,                   https://www.youtube.com/watch?v=kjG6h_LTklo",
            "https://youtu.be/JeTQ0q46pBM?si=I2jXMZv8HgWHPHOu,            https://www.youtube.com/watch?v=JeTQ0q46pBM",
            "https://youtube.com/shorts/Rjfzpzj3bug?si=kIgd6023DmmZxDLG, https://www.youtube.com/shorts/Rjfzpzj3bug",
            "https://www.youtube.com/shorts/T-JwDP_5hEY/,                https://www.youtube.com/shorts/T-JwDP_5hEY",
            "'  https://youtu.be/JeTQ0q46pBM  ',                         https://www.youtube.com/watch?v=JeTQ0q46pBM"
    })
    @DisplayName("공유 링크 형식을 받아 종류별 저장 형태로 정규화한다")
    void normalizesSupportedLinks(String input, String canonical) {
        assertThat(YouTubeUrl.parse(input)).get()
                .extracting(YouTubeUrl::canonicalUrl).isEqualTo(canonical);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {
            "https://www.instagram.com/p/DD1ajNQyrBH/",
            "https://example.com/watch?v=kjG6h_LTklo",
            "https://www.youtube.com/channel/UCabcdefghijk",
            "https://www.youtube.com/playlist?list=PL123",
            "https://www.youtube.com/embed/kjG6h_LTklo",
            "https://www.youtube.com/live/kjG6h_LTklo",
            "https://music.youtube.com/watch?v=kjG6h_LTklo",
            "https://www.youtube.com/watch?list=PL123",
            "https://youtu.be/short",
            "https://youtu.be/kjG6h_LTklo1",
            "https://youtu.be",
            "https://youtu.be?si=x",
            "https://youtu.be/kjG6h_LTklo/",
            "https://www.youtube.com/watch?v=kjG6h_LTklo/",
            "youtu.be/kjG6h_LTklo",
            "javascript:alert(1)",
            "not a url"
    })
    @DisplayName("YouTube 영상 공유 링크가 아니면 거절한다")
    void rejectsOtherLinks(String input) {
        assertThat(YouTubeUrl.parse(input)).isEmpty();
    }

    @Test
    @DisplayName("썸네일은 영상 id 로 만든 hqdefault 주소다")
    void thumbnailUsesVideoId() {
        assertThat(YouTubeUrl.parse("https://youtube.com/shorts/Rjfzpzj3bug").orElseThrow().thumbnailUrl())
                .isEqualTo("https://i.ytimg.com/vi/Rjfzpzj3bug/hqdefault.jpg");
    }
}
