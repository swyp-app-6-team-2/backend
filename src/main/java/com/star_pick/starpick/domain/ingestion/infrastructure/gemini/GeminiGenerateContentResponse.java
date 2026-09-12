package com.star_pick.starpick.domain.ingestion.infrastructure.gemini;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
record GeminiGenerateContentResponse(
        List<Candidate> candidates,
        PromptFeedback promptFeedback,
        UsageMetadata usageMetadata) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Candidate(GeminiContent content, String finishReason) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PromptFeedback(String blockReason) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record UsageMetadata(Integer promptTokenCount, Integer candidatesTokenCount) {
    }
}
