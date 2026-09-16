# 보상형 광고를 통한 레시피 슬롯 확장 — 기술 설계

작성일: 2026-09-16

상태: 구현 전 설계안. 이 문서는 기존 SSV 문서를 보완한 새 문서이며, 기능 구현·마이그레이션 적용·실제 AdMob 연동 테스트 완료를 의미하지 않는다.

## 1. 목적과 범위

사용자가 보상형 광고를 시청하면 서버 검증 후 레시피 저장 한도를 2개 늘린다. 하루 최대 3회 지급하며, 저장 한도 초과 안내와 마이페이지의 슬롯 확장 화면에서 동일한 기능을 사용한다.

구현 범위:
- 광고 이용 가능 횟수와 슬롯 상태 조회
- 인증된 사용자에게 시청 세션 발급 및 일일 지급 가능 횟수 예약
- AdMob SSV 서명·세션·광고 정책 검증
- 중복 지급 및 동시 요청 방지, 슬롯 증가와 이력의 원자적 저장
- 앱의 지급 결과 조회와 앱 재실행 후 복구

광고 SDK 로드·재생, 햅틱, 팝업 표시는 프런트 역할이다. 레시피 저장·삭제 정책 자체는 이 기능에서 변경하지 않는다.

## 2. 기본 정책

| 항목 | 정책 |
|---|---|
| 최초 저장 한도 | 기존 `User.recipeSlotLimit` 기본값 10 유지 |
| 광고 보상 | 검증된 광고 1건당 `recipeSlotLimit + 2` |
| 일일 최대 지급 | 사용자 계정당 3회, 최대 +6 |
| 시간대 | `Asia/Seoul` |
| 보상 유형 | `recipe_slot`로 통일 |
| 지급 권한 | 서버의 SSV 검증 결과만 사용 |
| 광고 미시청·로드 실패 | 슬롯 증가 및 성공 지급 횟수 증가 없음 |
| 슬롯 유효기간 | 이번 기능에서는 차감·만료를 추가하지 않음 |
| 계정 제한 | 존재하고 탈퇴하지 않은 사용자만 허용 |

기존 기능 명세의 `daily_watch_limit = 5`는 3으로 수정한다. 외부 응답은 camelCase를 사용한다. 클라이언트가 `userId`, 지급량, 일일 횟수 또는 한도를 결정하지 않는다.

현재 프로젝트에서 남은 슬롯은 `recipeSlotLimit - cumulativeRecipeCount`이며, `cumulativeRecipeCount`는 삭제를 포함한 누적 등록 횟수다. 광고 기능은 이 의미를 바꾸지 않는다. 현재 보관 중인 레시피 개수와 혼용하지 않는다.

### 2.1 날짜·만료 정책 — 이번 설계의 제안값

기존 자료에 없던 아래 값은 구현 기준으로 제안하며, 프런트와 공유 후 운영 설정으로 관리한다.

- 일일 집계 날짜 `quotaDate`는 서버가 시청 세션을 발급한 시각의 KST 날짜로 고정한다.
- 세션은 발급 후 30분 동안 광고 보상 이벤트가 발생할 수 있다. `expiresAt`을 응답한다.
- 이용 가능 횟수 1회를 발급 시 예약하고, SSV 지연을 고려해 발급 후 최대 24시간까지 해당 날짜의 예약을 유지한다. `verificationDeadline`을 응답한다.
- Google의 서명된 `timestamp`는 밀리초 epoch로 해석한다. 세션 생성 5분 전부터 `expiresAt`까지 허용하고, 수신 서버 시각보다 5분 이상 미래인 이벤트는 거절한다. 5분은 시계 오차 허용 설정이다.
- 정상 이벤트가 24시간 내 도착하면 세션의 원래 날짜에 지급한다. 23:59에 발급받은 세션이 다음 날 검증돼도 전날 횟수에 포함한다.
- 24시간을 넘기면 자동 지급하지 않고 만료 결과를 기록한다. 이후 유효한 콜백은 지연 거절 이력과 운영 알림을 남겨 확인 대상으로 삼는다. 수동 지급 기능은 이번 범위에 포함하지 않는다.
- 진행 중인 세션은 사용자당 1개만 허용한다. 전날 진행 중 세션은 새날의 시청을 막지 않도록 이 제한도 `quotaDate`별로 적용한다.

예약을 유지하면 앱 강제 종료 등으로 사용자가 당일 시청을 잠시 진행하지 못할 수 있다. 따라서 세션 포기 API와 진행 중 세션 복구 UI를 제공한다. **포기를 확정한 세션에는 이후 SSV가 와도 자동 지급하지 않는다.** 포기 버튼에는 이 결과를 명시하고, `onUserEarnedReward` 수신 후에는 자동으로 포기하지 않는다.

일일 범위 조회는 `[당일 00:00, 다음 날 00:00)`를 사용한다. 일일 카운터는 `quotaDate`로 관리하며, 실제 지급 시각 `grantedAt`만으로 다시 집계하지 않는다.

## 3. 전체 흐름

1. 앱은 JWT로 광고 상태를 조회하고 지급 가능 횟수·진행 중 세션을 표시한다.
2. 광고 시청 버튼을 누르면 인증된 세션 발급 API를 호출한다.
3. 서버는 사용자와 일일 상태를 잠근 뒤 횟수를 예약하고 예측 불가능한 세션 ID를 발급한다.
4. 앱은 서버가 준 광고 단위로 광고를 로드하고, 표시 전에 SSV `custom_data`에 세션 ID를 설정한다. 광고 인스턴스와 세션을 1:1로 연결한다.
5. 앱은 `onUserEarnedReward`를 받으면 “보상 확인 중”을 표시하고 세션 결과를 조회한다. 이 콜백으로 슬롯을 직접 증가시키지 않는다.
6. Google이 공개 SSV 콜백을 호출한다. 서버는 원문 서명과 세션·광고 정책을 검증한다.
7. 하나의 DB 트랜잭션에서 지급 이력 저장, 예약 전환, 성공 횟수 증가, 사용자 슬롯 +2를 완료한다.
8. 앱은 `GRANTED` 결과를 확인한 뒤 완료 팝업을 표시한다. 확인 버튼은 이전 화면으로 복귀하며 레시피 자동 저장을 의미하지 않는다.

SSV는 앱 콜백보다 먼저 또는 늦게 도착할 수 있다. 어느 순서에서도 같은 결과를 반환해야 한다.

## 4. API 제안

아래 경로는 신규 제안이다. 앱용 API는 기존 JWT와 `ApiResponse`를 사용하고, Google 콜백만 별도 규칙을 사용한다.

### 4.1 광고 상태 조회

`GET /api/v1/ads/rewards/status` — JWT 필수

```json
{
  "status": 200,
  "message": "광고 보상 상태를 조회했습니다.",
  "data": {
    "recipeSlotLimit": 12,
    "remainingRecipeSlots": 2,
    "dailyRewardCount": 1,
    "dailyRewardLimit": 3,
    "reservedCount": 0,
    "remainingRewardCount": 2,
    "availableWatchCount": 2,
    "canWatchAd": true,
    "unavailableReason": null,
    "quotaDate": "2026-09-16",
    "resetsAt": "2026-09-16T15:00:00Z",
    "pendingSessions": []
  }
}
```

- `remainingRewardCount = dailyRewardLimit - dailyRewardCount`: 아직 지급받지 않은 횟수.
- `availableWatchCount = dailyRewardLimit - dailyRewardCount - reservedCount`: 예약을 제외한 횟수.
- `canWatchAd`는 당일 진행 중 세션이 없어야 true다. `unavailableReason`은 `DAILY_LIMIT_REACHED`, `REWARD_PENDING` 등을 사용한다.
- 진행 중 세션은 `sessionId`, `status`, `quotaDate`, `expiresAt`, `verificationDeadline`을 포함한다. 전날의 미완료 세션도 복구할 수 있도록 반환한다.
- UI의 잔여 횟수와 지급 대기 상태를 구분한다. 지급 대기를 “오늘 횟수 모두 사용”으로 표시하지 않는다.

### 4.2 시청 세션 발급

`POST /api/v1/ads/rewards/sessions` — JWT 필수

```json
{ "platform": "ANDROID", "requestId": "client-generated-uuid" }
```

- 플랫폼은 `ANDROID` 또는 `IOS`. 플랫폼별 허용 광고 단위는 서버 설정으로 선택한다.
- `requestId`는 버튼 재터치·네트워크 재시도용 멱등 키다. `(userId, requestId)`를 UNIQUE로 관리한다.
- 동일 키·동일 요청은 기존 세션을 반환하고, 동일 키에 다른 플랫폼이면 409를 반환한다. 새 광고 시도에는 새 키가 필요하다.
- 새 키로 요청했으나 당일 진행 중 세션이 있으면 `AD_REWARD_SESSION_PENDING` 409. 상태 조회로 기존 세션을 복구한다.
- 남은 횟수가 없으면 `AD_REWARD_DAILY_LIMIT_REACHED` 409.
- 성공 및 동일 요청 재조회는 200.

```json
{
  "status": 200,
  "message": "광고 시청 세션을 발급했습니다.",
  "data": {
    "sessionId": "server-generated-random-uuid",
    "status": "PENDING",
    "adUnitId": "configured-ad-unit-id",
    "customData": "server-generated-random-uuid",
    "rewardType": "recipe_slot",
    "rewardAmount": 2,
    "quotaDate": "2026-09-16",
    "expiresAt": "2026-09-16T12:30:00Z",
    "verificationDeadline": "2026-09-17T12:00:00Z"
  }
}
```

`sessionId`는 인증 토큰이나 순차 사용자 ID로 만들지 않는다. 세션 소유자는 JWT에서 결정한다. SSV `user_id`는 필수가 아니며, 사용할 경우에도 그것만으로 사용자를 선택하지 않는다.

### 4.3 세션 결과 조회

`GET /api/v1/ads/rewards/sessions/{sessionId}` — JWT 필수, 본인 세션만 조회

응답 `data`:
- `sessionId`, `status`, `reasonCode`, `quotaDate`
- `grantedAmount`: 미지급이면 0, 지급 완료면 2
- `grantedAt`: 미지급이면 null
- `recipeSlotLimit`, `remainingRecipeSlots`: 조회 시점의 최신 값

상태: `PENDING`, `GRANTED`, `CANCELLED`, `EXPIRED`, `REJECTED`.

없는 세션 및 다른 사용자의 세션은 동일한 404로 응답한다. 날짜가 바뀌어도 기존 세션 결과를 조회할 수 있다.

앱은 광고 콜백 수신 후 예를 들어 2초 간격으로 최대 30초 조회하고, 이후에는 대기 안내와 재조회 기능을 제공한다. 폴링 중단은 지급 실패나 취소를 의미하지 않는다. 화면 복귀·앱 재실행 때 다시 조회한다. 폴링 간격은 서버 부하와 실측 지연을 보고 조정한다.

### 4.4 세션 포기

`POST /api/v1/ads/rewards/sessions/{sessionId}/cancel` — JWT 필수, 본인 세션만

```json
{ "reason": "LOAD_FAILED" }
```

`LOAD_FAILED`, `USER_DISMISSED`, `USER_ABANDONED`를 허용한다. 클라이언트 보고는 진짜 시청 여부에 대한 증거가 아니라 해당 세션의 보상 청구를 포기하는 요청이다.

- 잠금 안에서 `PENDING → CANCELLED`로 전환하고 해당 날짜 예약을 반환한다.
- 이미 `GRANTED`라면 지급을 취소하지 않고 현재 결과를 200으로 반환한다.
- 이미 취소·만료·거절이면 기존 결과를 반환한다. 반복 요청은 예약을 중복 반환하지 않는다.
- 콜백과 취소가 경쟁하면 잠금으로 먼저 확정된 결과를 따른다. 취소 이후 콜백은 거절 이력만 저장한다.
- 광고 닫힘 이벤트가 지급 콜백보다 먼저 오는 경우를 고려한다. 단순 화면 닫힘만으로 즉시 취소 API를 호출하지 않고, SDK 이벤트 순서 확인 또는 사용자 명시적 포기로 처리한다.

### 4.5 Google SSV 콜백

`GET /api/v1/ads/rewards/callback` — 사용자 JWT 없음, Google 서명 검증 필수

주요 파라미터: `ad_unit`, `custom_data`, `reward_amount`, `reward_item`, `timestamp`, `transaction_id`, `signature`, `key_id`. 나머지 Google 파라미터도 원문 서명 검증 대상에서 임의 제거하지 않는다.

- 이 정확한 GET 경로만 인증 예외로 허용한다. 나머지 광고 API는 JWT가 필요하다.
- 성공·이미 처리한 거래·영구 거절 기록 완료: 빈 body의 200.
- 잘못된 서명·구문: 400, 지급 없음.
- 공개키 조회·DB 등 일시 장애: 5xx, 성공 처리로 삼지 않는다.
- 콜백 응답은 공통 사용자용 JSON으로 감싸지 않는다. 예외 경로에도 이 규칙을 적용한다.
- 200은 지급 성공이 아니라 콜백 처리가 완료됐다는 의미다. 앱은 세션 상태를 조회한다.

## 5. 서명 및 업무 검증

의존성: `com.google.crypto.tink:apps-rewardedads:1.14.0`.

1. 원문 query string을 유지한다. 파라미터 순서 변경, 전체 decode 후 재조합을 하지 않는다.
2. Tink `RewardedAdsVerifier`로 서명을 검증한다. 검증기는 Bean으로 주입하여 테스트 대체가 가능하게 한다.
3. 필수 값 누락·중복 파라미터·빈 값·길이 초과·숫자 변환 및 범위 오류를 명시적으로 처리한다. 서명 검증과 업무 파라미터 해석이 서로 다른 값을 선택하지 않게 한다.
4. `transaction_id` 처리 이력을 확인한다. 기존 결과가 있으면 추가 지급 없이 종료한다.
5. `custom_data`로 서버 발급 세션을 찾고 해당 세션의 사용자·상태·유효시간을 확인한다.
6. 실제 콜백 `ad_unit`을 세션의 플랫폼별 기대 값과 대조한다. SDK 광고 단위 표기와 콜백 표기는 실제 AdMob 테스트로 확인해 설정에 명시한다.
7. `reward_item == recipe_slot`, `reward_amount == 2`를 검증한다. 증가량은 서버 상수/정책의 2를 사용한다.
8. 서명된 이벤트 시각, 수신 마감, 계정 활성 상태를 검증한다.
9. 잠금 안에서 세션 및 일일 한도를 다시 확인한 후 지급한다.

유효한 서명은 클라이언트가 지정한 사용자 식별자가 로그인 사용자임을 보장하지 않는다. 세션 연결을 통해 이를 보완한다.

서명 불일치와 공개키 다운로드 장애를 동일한 오류로 취급하지 않는다. 라이브러리의 예외 원인과 동작은 구현 시 확인한다. 공개키 캐시·키 교체·다운로드 타임아웃을 검증하며, “라이브러리가 관리하므로 운영 확인 불필요”로 서술하지 않는다.

외부 공개키 조회는 DB 잠금 트랜잭션 밖에서 수행한다. 검증 후 지급 트랜잭션에 진입해 DB 상태를 재검사한다. Spring 프록시를 거치도록 별도 트랜잭션 협력 객체 또는 `TransactionTemplate`을 사용하며, 같은 객체 내부 호출만으로 `@Transactional`이 동작한다고 가정하지 않는다.

## 6. 데이터 설계

아래는 논리 스키마다. 실제 Flyway 파일 번호·명명 규칙은 구현 시 최신 브랜치와 맞춘다. 이 문서에서는 SQL을 적용하지 않는다.

### `ad_reward_daily_quota`

| 컬럼 | 의미 |
|---|---|
| user_id | users FK |
| quota_date | KST 집계 날짜 |
| granted_count | 성공 지급 횟수 |
| reserved_count | 미완료 예약 횟수 |

PK `(user_id, quota_date)`. 두 카운터는 음수가 아니며 합계는 3 이하. 일일 한도를 설정화할 경우 DB 제약과 정책 변경 절차를 함께 관리한다. 행 생성 역시 사용자 잠금 안에서 수행한다.

### `ad_reward_session`

| 컬럼 | 의미 |
|---|---|
| id | 임의 생성 UUID, PK |
| user_id | users FK |
| request_id | 사용자별 멱등 키 |
| platform / expected_ad_unit | 발급 시 광고 정책 |
| reward_type / reward_amount | `recipe_slot` / 2 |
| quota_date | 발급 시 고정된 집계 날짜 |
| status / reason_code | 처리 상태 및 사유 |
| created_at / expires_at / verification_deadline | 시각 경계 |
| granted_at / cancelled_at | 실제 상태 변경 시각, 해당 없으면 null |

UNIQUE `(user_id, request_id)`. 사용자·날짜·상태 조회 인덱스. 시각은 `TIMESTAMPTZ`, 집계 날짜는 `DATE`.

### `ad_reward_transaction`

| 컬럼 | 의미 |
|---|---|
| id | PK |
| transaction_id | Google 거래 ID, 전역 UNIQUE |
| session_id / user_id | 확인된 세션·사용자 FK, 불일치/미존재 콜백은 null 허용 |
| status / reason_code | `GRANTED` 또는 `REJECTED`와 사유 |
| ad_unit / reward_item / received_reward_amount | 검증된 콜백 정보 |
| reward_event_at / received_at / processed_at | 이벤트·수신·처리 시각 |
| granted_amount / granted_at | 실제 지급량, 거절이면 0 / null |

`status = GRANTED`인 `session_id`에는 부분 UNIQUE 인덱스를 두어 하나의 세션에 서로 다른 거래 ID가 와도 한 번만 지급되게 한다. 지급 금액·상태 조합에 CHECK 제약을 둔다.

서명은 유효하지만 업무 검증에서 거절한 거래도 기록한다. 거래 ID가 없는 잘못된 요청은 별도 관측 로그로 남기고 지급 이력으로 취급하지 않는다. 외부 입력에 길이 제한을 적용하고, 감사 로그 저장 문제로 지급 검증을 우회하지 않는다.

세션의 영구 거절은 예약도 한 번만 반환한다. 다만 이미 취소·지급된 세션에 도착한 추가 거래의 거절이 기존 세션 결과를 덮어쓰면 안 된다.

## 7. 트랜잭션과 동시성

모든 변경 경로의 잠금 순서를 통일한다: `users → daily_quota → session`.

지급 트랜잭션:
1. 세션에서 사용자 ID를 확인한 뒤 사용자 행을 `findByIdForUpdate()`로 잠근다.
2. 일일 행과 세션을 잠그고, 상태·계정·기존 거래를 재확인한다.
3. 예약이 1건 존재하고 성공 횟수가 3 미만임을 확인한다.
4. 거래 지급 기록 저장, `reserved_count - 1`, `granted_count + 1`, 세션 `GRANTED`, 사용자 슬롯 +2를 함께 커밋한다.
5. 실패하면 모두 롤백한다. 지급 기록과 슬롯 증가를 독립 트랜잭션으로 분리하지 않는다.

`existsByTransactionId()`는 빠른 경로일 뿐 중복 방지의 최종 장치가 아니다. UNIQUE 충돌 시 실패한 트랜잭션을 종료한 뒤 별도 조회로 이미 처리된 거래인지 확인한다. 롤백 전용 트랜잭션에서 예외만 삼키고 계속 처리하지 않는다. 일반 DB 오류를 중복으로 오인하지 않는다.

현재 `UserRecipeStatsService.onRecipeCreated()`는 일반 `findById()`를 사용한다. 광고와 레시피 생성이 동일 사용자 행을 변경하므로 관련 쓰기 경로의 잠금/낙관적 버전 정책을 점검하고 통일한다. 지급 메서드에서만 늦게 잠금을 잡고 그 전에 횟수를 검사하면 안 된다.

만료 처리 역시 같은 잠금 순서로 예약을 한 번만 반환한다. 세션 발급 시 필요한 만료 정리와 주기적 만료 작업을 함께 제공한다. 읽기 GET에서 지급을 수행하지 않는다.

## 8. 장애 및 프런트 화면 처리

| 상황 | 서버/앱 처리 |
|---|---|
| 성공 지급 3회 | 버튼 비활성화, 다음 KST 날짜 안내 |
| 보상 검증 대기 | 별도 대기 안내, 중복 광고 시작 방지 |
| 광고 로드 실패 | 실패 안내 및 재시도/취소. 기존 세션 포기 완료 후 새 세션 발급 |
| 광고 중도 이탈 | 슬롯 지급 없음. 이벤트 순서 확인 후 포기 처리, 에러 팝업 불필요 |
| 앱 종료·네트워크 단절 | 자동 취소하지 않음. 재진입 시 상태 조회 |
| SSV 지연 | 대기 표시, 서버 지급 완료 후 성공 화면 |
| 공개키/DB 장애 | 5xx 및 알림, 지급과 기록 전체 롤백 |
| 정책 불일치 | 영구 거절 기록, 앱에 reasonCode 제공, 필요 시 운영 알림 |
| 이미 지급된 거래 재수신 | 200, 추가 증가 없음 |

Google의 자동 재시도는 유한하다. 서명 검증에 성공한 콜백은 민감 정보가 노출되지 않는 제한된 운영 저장소/로그에 추적 가능하게 기록하고, 반복 장애·지연 만료·설정 불일치에 알림을 둔다. 원문 query에는 세션 정보가 포함되므로 일반 접근 로그에 그대로 노출하지 않는다.

재시도 소진 이후의 수동 복구는 원본 검증 정보와 지급 이력을 확인하고 동일한 거래/세션 멱등 제약을 통과하는 절차로 수행해야 한다. 복구 절차 없이 임의 SQL로 슬롯만 증가시키지 않는다. 내구성 있는 콜백 inbox/작업 큐는 운영 확장안이며, 이번 동기 설계의 장애 시 유실 가능성을 완전히 제거했다고 주장하지 않는다.

## 9. 구현 위치와 설정

- 광고 도메인의 컨트롤러/서비스는 `AdRewardController`, `AdRewardService`를 중심으로 구성한다.
- 요청/응답 DTO는 광고 도메인의 `dto/request`, `dto/response`에 배치한다.
- 사용자 슬롯 증가는 기존 `UserRecipeStatsService`와 `User.increaseRecipeSlotLimit()`을 활용하되 검증·잠금·트랜잭션 경계를 맞춘다.
- 설정: 플랫폼별 광고 단위와 콜백 기대 ID, 보상 유형/지급량, 일일 한도, 시간대, 세션 시간, 검증 마감, 시계 오차 허용량.
- 환경별 광고 설정을 분리한다. 운영 콜백이 개발용 광고/세션을 지급하지 않게 한다.
- SSV 콜백 URL은 Google에서 접근 가능한 HTTPS 주소로 구성한다. 토큰을 URL에 넣지 않는다.
- 시각 판단은 주입 가능한 `Clock`을 사용한다.
- 로그 보관 및 사용자 탈퇴 시 FK/개인정보 처리 방식은 기존 프로젝트 정책과 맞춘다. 사용자 삭제를 무조건 CASCADE하여 중복 방지 기록을 지우지 않는다.

## 10. 테스트 및 완료 기준

### 자동화 테스트

- [ ] JWT 필수 API 접근 제어, 다른 사용자 세션 조회·취소 차단
- [ ] 발급 멱등성: 동일 requestId 재시도 및 서로 다른 요청의 동시 발급
- [ ] 일일 횟수 예약·반환·성공 전환과 음수/초과 방지
- [ ] 같은 거래 순차/동시 수신 시 한 번만 +2
- [ ] 같은 세션에 다른 거래 ID가 와도 한 번만 +2
- [ ] 지급 2회 상태의 동시 요청에도 최대 3회 유지
- [ ] 여러 사용자 병렬 처리 시 보상과 횟수 격리
- [ ] 지급 거절 거래의 다음 날 재전송에도 지급 없음
- [ ] 광고 단위·보상 항목·지급량·사용자·세션 불일치 차단
- [ ] 숫자 오류·중복 파라미터·누락·과도한 길이 처리
- [ ] KST 자정, 세션 만료, 시각 오차, 24시간 지연 마감 경계
- [ ] 취소/만료와 SSV 경쟁 시 예약 중복 반환 및 이중 지급 없음
- [ ] 슬롯 변경·거래 저장 중 오류 시 전체 롤백
- [ ] 레시피 생성과 광고 지급 동시 실행 시 카운터 손실 없음
- [ ] 공개키 회전·일시 장애·서명 변조 및 원문 인코딩 처리
- [ ] 만료 작업 재실행 및 여러 인스턴스 실행의 멱등성

서명 검증 테스트는 테스트 키로 서명한 fixture와 검증기 단위 테스트를 사용한다. 업무 테스트에서는 검증기 대역을 사용할 수 있으나, 서명 통합 테스트를 그것만으로 대체하지 않는다. 잠금·부분 UNIQUE 등은 실제 PostgreSQL 기반 통합 테스트로 검증한다.

### 실제 연동 확인 — 별도 환경 필요

- [ ] AdMob 콘솔 SSV 테스트 도구로 외부 콜백 도달과 서명 검증 확인
- [ ] Android/iOS의 실제 사용 SDK에서 custom_data 전달과 광고 ID 형태 확인
- [ ] Google 테스트 광고로 보상/닫힘 이벤트 순서와 결과 폴링 확인
- [ ] 앱 종료·재시작, 네트워크 지연, 취소 후 재시도 흐름 확인
- [ ] 운영 광고 설정 및 모니터링 점검

자동화 테스트 통과와 실제 AdMob 연동 검증은 구분해 결과를 기록한다. 현재 문서는 두 테스트를 수행했다는 보고서가 아니다.

## 11. 공식 참고 자료

- [AdMob SSV 검증 문서](https://developers.google.com/admob/android/ssv): 콜백 파라미터, 검증, 재시도, 공개키 관리
- [보상 지급 시점](https://developers.google.com/admob/android/ssv#rewarding_the_user): 즉시 지급과 서버 검증 대기 방식의 선택
- [SSV custom_data](https://developers.google.com/admob/android/ssv#custom_data): 서버 발급 세션 전달에 사용
- [Tink Java Apps](https://github.com/tink-crypto/tink-java-apps): apps-rewardedads 의존성

라이브러리 버전·캐시·SDK 동작은 구현 시점의 공식 문서와 실제 의존성으로 다시 검증한다.
