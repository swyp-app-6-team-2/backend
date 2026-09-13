package com.star_pick.starpick.domain.ingestion.service;

import java.util.List;

/** 분석할 입력. 사진이면 {@code images}, 영상이면 {@code videoUrl} 에만 값이 있다. */
public record AnalysisInput(List<InlineImage> images, String videoUrl) {

    public static AnalysisInput ofImages(List<InlineImage> images) {
        return new AnalysisInput(images, null);
    }

    public static AnalysisInput ofVideo(String videoUrl) {
        return new AnalysisInput(List.of(), videoUrl);
    }
}
