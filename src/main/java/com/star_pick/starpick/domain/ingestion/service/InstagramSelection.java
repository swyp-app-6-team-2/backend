package com.star_pick.starpick.domain.ingestion.service;

import com.star_pick.starpick.domain.ingestion.domain.IngestionFailureCode;
import java.util.List;
import java.util.Objects;

/**
 * embed 에서 무엇을 분석할지 고른 결과. {@code failureCode} 가 있으면 Gemini 를 부르지 않는다.
 *
 * <p>분기 기준은 링크 종류가 아니라 embed 의 미디어 구성이다. 서버가 여러 카드 중 하나를 임의로 고르지 않는다.
 */
public record InstagramSelection(List<String> imageUrls, String videoUrl, String previewImageUrl,
                                 IngestionFailureCode failureCode) {

    public static InstagramSelection of(InstagramPost post, Integer imgIndex) {
        List<InstagramMedia> media = post.media();
        if (media.size() == 1) {
            InstagramMedia only = media.getFirst();
            return only.video() ? video(only) : images(List.of(only));
        }
        if (imgIndex != null) {
            if (imgIndex > media.size()) {
                return failed(null, IngestionFailureCode.SOURCE_UNAVAILABLE);
            }
            InstagramMedia card = media.get(imgIndex - 1);
            // 영상 카드 분석은 제품 결정 전이다(spec OQ5).
            return card.video()
                    ? failed(card.displayUrl(), IngestionFailureCode.CONTENT_NOT_RECOGNIZED)
                    : images(List.of(card));
        }
        List<InstagramMedia> imageCards = media.stream().filter(card -> !card.video()).toList();
        return imageCards.isEmpty()
                ? failed(media.getFirst().displayUrl(), IngestionFailureCode.CONTENT_NOT_RECOGNIZED)
                : images(imageCards);
    }

    /** 카드 하나라도 주소가 없으면 전부를 분석한다는 약속을 지킬 수 없으므로 원본을 쓸 수 없는 것으로 본다. */
    private static InstagramSelection images(List<InstagramMedia> cards) {
        String preview = cards.stream().map(InstagramMedia::displayUrl).filter(Objects::nonNull).findFirst().orElse(null);
        if (cards.stream().anyMatch(card -> card.displayUrl() == null)) {
            return failed(preview, IngestionFailureCode.SOURCE_UNAVAILABLE);
        }
        return new InstagramSelection(cards.stream().map(InstagramMedia::displayUrl).toList(), null, preview, null);
    }

    private static InstagramSelection video(InstagramMedia media) {
        if (media.videoUrl() == null) {
            return failed(media.displayUrl(), IngestionFailureCode.SOURCE_UNAVAILABLE);
        }
        return new InstagramSelection(List.of(), media.videoUrl(), media.displayUrl(), null);
    }

    private static InstagramSelection failed(String previewImageUrl, IngestionFailureCode failureCode) {
        return new InstagramSelection(List.of(), null, previewImageUrl, failureCode);
    }
}
