package com.star_pick.starpick.domain.ingestion.service;

import java.util.List;

/** 분석할 입력. {@code source} 에 따라 {@code images}·{@code videoUrl}·{@code caption} 중 필요한 것만 값이 있다. */
public record AnalysisInput(Source source, List<InlineImage> images, String videoUrl, String caption) {

    public enum Source { PHOTOS, YOUTUBE, INSTAGRAM_POST, INSTAGRAM_REEL }

    public static AnalysisInput ofImages(List<InlineImage> images) {
        return new AnalysisInput(Source.PHOTOS, images, null, null);
    }

    public static AnalysisInput ofVideo(String videoUrl) {
        return new AnalysisInput(Source.YOUTUBE, List.of(), videoUrl, null);
    }

    public static AnalysisInput ofInstagramPost(List<InlineImage> images, String caption) {
        return new AnalysisInput(Source.INSTAGRAM_POST, images, null, caption);
    }

    public static AnalysisInput ofInstagramReel(String fileUri, String caption) {
        return new AnalysisInput(Source.INSTAGRAM_REEL, List.of(), fileUri, caption);
    }
}
