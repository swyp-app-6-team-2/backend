package com.star_pick.starpick.domain.ingestion.infrastructure.gemini;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import tools.jackson.databind.JsonNode;

@JsonInclude(JsonInclude.Include.NON_NULL)
record GeminiGenerateContentRequest(
        List<GeminiContent> contents,
        GeminiContent systemInstruction,
        GeminiGenerationConfig generationConfig) {
}

@JsonInclude(JsonInclude.Include.NON_NULL)
record GeminiContent(String role, List<GeminiPart> parts) {
}

@JsonInclude(JsonInclude.Include.NON_NULL)
record GeminiPart(String text, GeminiInlineData inlineData) {
}

@JsonInclude(JsonInclude.Include.NON_NULL)
record GeminiInlineData(String mimeType, String data) {
}

@JsonInclude(JsonInclude.Include.NON_NULL)
record GeminiGenerationConfig(String responseMimeType, JsonNode responseJsonSchema) {
}
