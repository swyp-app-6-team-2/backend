package com.star_pick.starpick.domain.ingestion.service;

import com.star_pick.starpick.domain.ingestion.domain.RecipeDraft;

public record AnalysisOutcome(Verdict verdict, RecipeDraft draft, TokenUsage usage) {
}
