package com.star_pick.starpick.domain.ingestion.service;

import java.nio.file.Path;
import java.time.Duration;

public interface RecipeAnalyzer {

    AnalysisOutcome analyze(AnalysisInput input, Duration timeout);

    /** 영상을 분석용 저장소에 올린다. 올린 직후에는 보통 아직 {@code PROCESSING} 이다. */
    UploadedVideo uploadVideo(Path file, long size, Duration timeout);

    VideoFileState videoState(UploadedVideo video, Duration timeout);

    void deleteVideo(UploadedVideo video, Duration timeout);
}
