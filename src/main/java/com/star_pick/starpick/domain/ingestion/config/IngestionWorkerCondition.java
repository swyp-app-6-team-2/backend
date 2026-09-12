package com.star_pick.starpick.domain.ingestion.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;

/**
 * 이 프로세스가 Ingestion Worker 역할을 맡을지 정한다.
 *
 * <p><b>별도의 on/off 설정을 두지 않고 Gemini 키가 있는지로 정한다.</b> 스위치를 따로 두면 운영
 * 배포에서 켜는 것을 잊었을 때 앱이 정상으로 보이면서 분석만 멈춘다 — 기동도 헬스체크도 통과하고
 * 오류 로그도 없이 Job 이 {@code QUEUED} 로만 쌓인다. 키가 없으면 어차피 분석할 수 없으므로
 * 키의 유무가 곧 답이고, 그러면 잊을 수 있는 설정이 하나도 남지 않는다.
 *
 * <p>키가 없는 팀원 로컬은 앱이 그대로 뜨고 Worker 만 맡지 않는다. Ingestion 과 무관한 작업을
 * 하려고 Gemini 키를 받아야 하는 상황을 만들지 않기 위해서다.
 */
class IngestionWorkerCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Environment environment = context.getEnvironment();
        // 가짜 분석기를 끼우는 테스트 환경. 주기 작업이 돌면 시각 컬럼을 과거로 조작하는 다른
        // 테스트의 픽스처를 건드려 flaky 를 만든다(통합 테스트가 컨테이너 하나를 공유한다).
        if (!environment.getProperty("ingestion.external.enabled", Boolean.class, true)) {
            return false;
        }
        return StringUtils.hasText(environment.getProperty("ingestion.gemini.api-key"));
    }
}
