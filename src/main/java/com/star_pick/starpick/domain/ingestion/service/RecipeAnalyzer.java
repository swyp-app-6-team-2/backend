package com.star_pick.starpick.domain.ingestion.service;

import java.time.Duration;

public interface RecipeAnalyzer {

    AnalysisOutcome analyze(AnalysisInput input, Duration timeout);
}
