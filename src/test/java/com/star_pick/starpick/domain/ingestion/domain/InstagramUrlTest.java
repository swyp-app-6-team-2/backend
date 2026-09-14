package com.star_pick.starpick.domain.ingestion.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class InstagramUrlTest {

    @ParameterizedTest
    @CsvSource({
            "https://www.instagram.com/p/DD1ajNQyrBH/?utm_source=ig_web_copy_link&stkn=NTc4MTIwNjQ2YQ%3D%3D&img_index=3, https://www.instagram.com/p/DD1ajNQyrBH/?img_index=3",
            "https://www.instagram.com/p/DUj8E6uk5_I/?stkn=NnllZWE3ejE5N2hj,         https://www.instagram.com/p/DUj8E6uk5_I/",
            "http://instagram.com/p/DG-knu8yc-i?img_index=10,                         https://www.instagram.com/p/DG-knu8yc-i/?img_index=10",
            "https://m.instagram.com/mine.table_/p/DG-knu8yc-i/,                      https://www.instagram.com/p/DG-knu8yc-i/",
            "https://www.instagram.com/sinnaerin_chef/reel/DcdllvBmOgm/,              https://www.instagram.com/reel/DcdllvBmOgm/",
            "https://www.instagram.com/reel/DcdllvBmOgm/?igsh=abc&img_index=2,        https://www.instagram.com/reel/DcdllvBmOgm/",
            "'  https://www.instagram.com/p/DKI9fBzy5FB/  ',                         https://www.instagram.com/p/DKI9fBzy5FB/"
    })
    @DisplayName("게시물·Reel 공유 링크를 받아 추적 쿼리를 버리고 게시물의 img_index 만 남긴다")
    void normalizesSupportedLinks(String input, String canonical) {
        assertThat(InstagramUrl.parse(input)).get()
                .extracting(InstagramUrl::canonicalUrl).isEqualTo(canonical);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {
            "https://www.youtube.com/watch?v=kjG6h_LTklo",
            "https://www.instagram.com/",
            "https://www.instagram.com/mine.table_/",
            "https://www.instagram.com/stories/mine.table_/3456789012/",
            "https://www.instagram.com/reels/DcdllvBmOgm/",
            "https://www.instagram.com/tv/DcdllvBmOgm/",
            "https://www.instagram.com/p/DG-knu8yc-i/embed/",
            "https://www.instagram.com/p/DG-knu8yc-i/%EB%A5%BC?img_index=10",
            "https://www.instagram.com/p/DG-knu8yc-i/?img_index=0",
            "https://www.instagram.com/p/DG-knu8yc-i/?img_index=100",
            "https://www.instagram.com/p/DG-knu8yc-i/?img_index=abc",
            "https://www.instagram.com/p/DG-knu8yc-i/?img_index",
            "https://www.instagram.com/p/DG-knu8yc-i/?img_index=3&img_index=4",
            "https://www.instagram.com/p/%44%47-knu8yc-i/",
            "https://www.instagram.com/p/abc/",
            "https://www.instagram.com/bad!name/p/DG-knu8yc-i/",
            "https://instagram.com.evil.test/p/DG-knu8yc-i/",
            "www.instagram.com/p/DG-knu8yc-i/",
            "ftp://www.instagram.com/p/DG-knu8yc-i/",
            "not a url"
    })
    @DisplayName("Instagram 게시물·Reel 링크가 아니면 거절한다")
    void rejectsOtherLinks(String input) {
        assertThat(InstagramUrl.parse(input)).isEmpty();
    }

    @Test
    @DisplayName("Worker 가 쓸 값을 저장한 링크에서 다시 얻는다")
    void exposesPartsForWorker() {
        InstagramUrl post = InstagramUrl.parse("https://www.instagram.com/p/DD1ajNQyrBH/?img_index=3").orElseThrow();
        assertThat(post).isEqualTo(new InstagramUrl("DD1ajNQyrBH", false, 3));
        assertThat(InstagramUrl.parse("https://www.instagram.com/reel/DcdllvBmOgm/").orElseThrow())
                .isEqualTo(new InstagramUrl("DcdllvBmOgm", true, null));
    }
}
