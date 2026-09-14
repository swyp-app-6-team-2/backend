package com.star_pick.starpick.domain.ingestion.infrastructure.gemini;

/** Files API 의 파일. 업로드 응답은 {@code {"file": {...}}}, 상태 조회 응답은 파일 자체다. */
record GeminiFile(String name, String uri, String state) {
}

record GeminiFileEnvelope(GeminiFile file) {
}
