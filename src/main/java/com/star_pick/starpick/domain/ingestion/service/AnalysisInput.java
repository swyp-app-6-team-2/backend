package com.star_pick.starpick.domain.ingestion.service;

import java.util.List;

/**
 * 분석할 입력. {@code source} 에 따라 {@code images}·{@code videoUrl}·{@code sourceText} 중 필요한 것만 값이 있다.
 *
 * <p>{@code sourceText} 는 원본이 함께 제공하는 텍스트다 — Instagram 은 게시물 캡션, YouTube 는 영상 설명란.
 * 둘 다 신뢰할 수 없는 외부 입력이라 같은 방식으로 다룬다.
 */
public record AnalysisInput(Source source, List<InlineImage> images, String videoUrl, String sourceText) {

    public enum Source { PHOTOS, YOUTUBE, INSTAGRAM_POST, INSTAGRAM_REEL }

    public static AnalysisInput ofImages(List<InlineImage> images) {
        return new AnalysisInput(Source.PHOTOS, images, null, null);
    }

    public static AnalysisInput ofVideo(String videoUrl, String description) {
        return new AnalysisInput(Source.YOUTUBE, List.of(), videoUrl, description);
    }

    public static AnalysisInput ofInstagramPost(List<InlineImage> images, String caption) {
        return new AnalysisInput(Source.INSTAGRAM_POST, images, null, caption);
    }

    public static AnalysisInput ofInstagramReel(String fileUri, String caption) {
        return new AnalysisInput(Source.INSTAGRAM_REEL, List.of(), fileUri, caption);
    }
}
