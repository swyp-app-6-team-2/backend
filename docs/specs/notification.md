# Notification Tech Spec

> **문서 버전**: v1 · **기준일**: 2026-09-14 · **이슈**: [#49 식사 알림 구현](https://github.com/swyp-app-6-team-2/backend/issues/49)

## 한눈에 보기

Notification은 사용자가 정한 요일·시각에 식사 리마인드 푸시를 보내고, 알림을 누른 사실을 기록한다.

| 질문 | 답변 |
|---|---|
| 무엇을 관리하는가? | 알림 설정(수신 여부·요일·시간대), 기기별 푸시 토큰, 발송·오픈 기록 |
| 사용자는 무엇을 할 수 있는가? | 알림 설정 조회·저장, 기기 토큰 등록·해제, 받은 알림 오픈 기록 |
| 어떤 API를 제공하는가? | 설정 GET/PUT, 토큰 PUT/DELETE, 오픈 기록 POST |
| 핵심 데이터는 무엇인가? | `notification_setting`, `push_token`, `push_log` |
| 어떤 외부 서비스를 쓰는가? | FCM (Firebase Admin SDK) |
| 핵심 기술 결정은 무엇인가? | 매분 작업 하나가 기록 → 발송 → 결과 반영. **한 번만 시도하고 재시도하지 않는다.** 설정한 그 분에만 보낸다 |
| MVP에서 제외하거나 감수하는 것은? | 3일 미접속 알림, 발송 재시도·대기열·크래시 복구, 알림함 조회 API. 서버 재기동·FCM 장애 중인 분의 알림은 빠진다 |

## 1. 개요

```text
앱: 설정 저장(PUT) + 토큰 등록(PUT)
서버: 매분 1초 → 이번 분에 보낼 대상 기록(push_log) → FCM 발송 → 결과 반영
앱: 푸시 선택 → 딥링크 이동 + 오픈 기록(POST)
```

## 2. 목표와 범위

### 2.1. 목표

- 사용자가 정한 요일·시각(`Asia/Seoul`)에 식사 리마인드 푸시를 보낸다.
- 알림을 누르면 앱이 딥링크로 이동하고, 서버는 최초 오픈 시각을 기록한다.
- 설정 화면(수신 토글, 시간대 이름·시각, 요일)과 온보딩 칩 선택을 지원한다. 칩 → 시간대 변환은 앱이 하고 설정 저장 API로 보낸다.

### 2.2. MVP 제외 범위

| 제외 | 이유 |
|---|---|
| 3일 미접속 알림 | 기능정의서에서 제외됐다(2026-09-15). 활동 시각 컬럼(`users.last_activity_at`)과 `User.updateLastActivity()`는 남기고 갱신 로직은 구현하지 않는다 |
| 발송 재시도·대기열·오래된 `PROCESSING` 복구 | 식사 리마인드 1건 누락은 체감이 작고, 재시도는 이미 받은 알림을 다시 보낼 수 있다 |
| 알림함·발송 이력 조회 API | 화면이 없다 |
| 설정 시각을 놓쳤을 때 뒤늦게 보내기 | 설정 직후 지난 시각 알림이 바로 나가는 문제가 생긴다 |

탈퇴 시 정리는 **2026-09-16에 구현했다**(이슈 #107). 아래 `도메인 협력`을 참고한다.

### 2.3. 도메인 협력

- **Account**: `users(user_id)`를 FK로 참조만 한다. 탈퇴 시에는 Account가 `NotificationCleanupService.deleteAllForUser`를 호출하고, Notification이 `push_log → push_token → notification_setting` 순으로 지운다. FK 순서를 지키기 위한 순서이며, Account가 사용자 행을 잠근 트랜잭션 안에서만 실행한다. 전체 흐름과 도메인 간 순서는 [User Withdrawal Spec](./user-withdraw.md)의 `처리 흐름`이 소유한다. 이미 FCM에 넘어간 푸시의 철회까지 보장하지는 않는다.
- **App/OS**: OS 알림 권한, 푸시 수신, 딥링크 라우팅.
- **Discovery**: 알림을 누른 뒤 이동하는 추천 화면.

## 3. 기술 설계

### 3.1. 데이터 모델

migration `V16__create_notification.sql`.

**`notification_setting`** — 사용자당 0..1개

| 컬럼 | 타입 | 제약 |
|---|---|---|
| `id` | bigint identity | PK |
| `user_id` | bigint | NOT NULL, UNIQUE, FK `users` |
| `enabled` | boolean | NOT NULL |
| `weekdays` | `text[]` | NOT NULL, 기본 `{}`, CHECK `MONDAY`~`SUNDAY`만 |
| `time_slots` | `jsonb` | NOT NULL, 기본 `[]`. `[{"label": "점심 알림", "time": "12:00"}]` |

- 서버가 `weekdays`는 월→일, `time_slots`는 시각 순으로 정렬해 저장하고 조회는 저장 순서를 그대로 쓴다.
- `time`은 `"HH:mm"` **문자열**로 저장한다. 발송 조회가 문자열로 비교하기 때문이다.
- 시간대를 자식 테이블이 아니라 `jsonb`에 두는 이유: 설정과 항상 함께 읽고 통째로 바꾸며 개별 조회·수정이 없다.
- 알림을 꺼도 요일·시간대는 남는다.

**`push_token`** — 토큰은 시스템 전체에서 1개

| 컬럼 | 타입 | 제약 |
|---|---|---|
| `id` | bigint identity | PK |
| `user_id` | bigint | NOT NULL, FK `users` |
| `token` | text | NOT NULL, UNIQUE |
| `platform` | varchar | NOT NULL, CHECK `IOS`/`ANDROID` |
| `active` | boolean | NOT NULL |

row를 지우지 않고 `active`로 끈다. `platform`은 발송에 쓰지 않지만 한 플랫폼만 실패하는 경우를 구분하는 값이다.

**`push_log`** — 알림 한 건(토큰 × 분)

| 컬럼 | 타입 | 제약 |
|---|---|---|
| `id` | bigint identity | PK. 푸시 `data.notificationId` |
| `user_id` | bigint | NOT NULL, FK `users`. 알림 주인 |
| `push_token_id` | bigint | NOT NULL, FK `push_token` |
| `scheduled_at` | timestamptz | NOT NULL. 발송 대상이 된 분 |
| `status` | varchar | NOT NULL, CHECK `PROCESSING`/`SENT`/`FAILED` |
| `opened_at` | timestamptz | NULL이면 미오픈 |

- `UNIQUE(push_token_id, scheduled_at)`이 같은 분에 같은 기기로 두 번 기록되는 것을 막는다.
- `user_id`를 따로 두는 이유: 토큰이 다른 계정으로 넘어가도 오픈 기록의 소유 판단이 유지된다.
- 제약이 만드는 것 외에 인덱스는 두지 않는다.

### 3.2. API

모든 API는 인증이 필요하다.

#### `GET /api/v1/notification-settings`

```json
{
  "status": 200,
  "message": "알림 설정을 조회했습니다.",
  "data": {
    "enabled": true,
    "weekdays": ["MONDAY", "FRIDAY"],
    "timeSlots": [{"label": "점심 알림", "time": "12:00"}]
  }
}
```

설정이 없으면 `{"enabled": false, "weekdays": [], "timeSlots": []}`이고 row를 만들지 않는다.

#### `PUT /api/v1/notification-settings` — 전체 교체

```json
{
  "enabled": true,
  "weekdays": ["MONDAY", "FRIDAY"],
  "timeSlots": [{"label": "점심 알림", "time": "12:00"}]
}
```

- 세 필드 모두 필수다. **켜져 있어도 `weekdays`·`timeSlots`가 비어 있을 수 있다**(보낼 대상이 없을 뿐이다).
- 응답 `200`, `data: null`, 메시지 `알림 설정이 저장되었습니다.`

| 요청 | 응답 |
|---|---|
| 필드 누락·`null`, 배열 요소 `null`, `label` 빈 값·공백·255자 초과, 요일 중복, 시각 중복 | `400 REQUEST_VALIDATION_FAILED` + `data.errors` |
| 없는 요일 값, `time`이 `HH:mm`이 아님(`"8:00"`·`"24:00"`·`"08:00:00"`) | `400 INVALID_REQUEST_FORMAT` |

#### `PUT /api/v1/push-tokens`

```json
{"token": "fcm-registration-token", "platform": "IOS"}
```

- 새 토큰은 등록, 같은 사용자의 재등록은 `platform`·`active` 갱신, 다른 계정에 있던 토큰은 현재 계정으로 옮기고 활성화한다.
- `token` 필수·512자 이하, `platform`은 `IOS`/`ANDROID`(그 외 `400 INVALID_REQUEST_FORMAT`).
- 응답 `200`, `data: null`, 메시지 `푸시 토큰이 저장되었습니다.`

#### `DELETE /api/v1/push-tokens`

```json
{"token": "fcm-registration-token"}
```

- 내 토큰이면 비활성화한다. 남의 토큰·없는 토큰이어도 아무것도 바꾸지 않고 `200`이다(소유 여부를 드러내지 않는다).
- `token` 필수·512자 이하. 위반하면 `400 REQUEST_VALIDATION_FAILED`.
- 응답 `200`, `data: null`, 메시지 `푸시 토큰이 해제되었습니다.`

#### 토큰 수명주기 — 앱의 책임

서버는 앱이 보낸 것만 안다. 등록 요청이 실패하면 서버에는 아무 기록도 남지 않고, 매분 발송 대상 조회는 그 사용자를 0행으로 넘긴다. 정상 종료라 로그에도 `push_log`에도 남지 않아 **서버에서는 "토큰 미등록"과 "이번 분에 보낼 알림 없음"을 구분할 수 없다.** 설정 저장은 `200`이라 앱에서는 정상으로 보인다.

| 시점 | 앱이 하는 일 | 서버 동작 | 하지 않으면 |
|---|---|---|---|
| 앱 실행 | accessToken이 있으면 `PUT /push-tokens` | 없으면 등록, 있으면 갱신·활성화 | 아래 누락이 영구화된다. 이 재등록이 유일한 자동 복구 경로다 |
| 로그인 성공 직후 | accessToken을 저장한 **뒤** `PUT` | 같음 | 다음 앱 실행 전까지 알림이 오지 않는다 |
| 회원가입 완료 직후 | 가입 응답의 accessToken을 저장한 **뒤** `PUT` | 같음 | 가입 시점엔 토큰이 없어 `401`. 신규 사용자가 알림을 설정해도 받지 못한다 |
| FCM 토큰 갱신 | 새 토큰으로 `PUT` | 새 행으로 등록 | 옛 토큰으로 계속 보내다 `UNREGISTERED`로 비활성화된다 |
| 로그아웃 | 액세스 토큰을 지우기 **전에** `DELETE /push-tokens` | 내 토큰이면 비활성화 | 그 기기에 이전 계정 알림이 계속 간다 |
| 계정 전환 | 새 계정으로 `PUT` | 토큰을 새 계정으로 옮기고 활성화 | 이전 계정 알림이 계속 간다 |
| 회원 탈퇴 | 없음 | 설정·토큰·발송 기록을 함께 지운다 | — |
| OS 알림 권한 거부 | 없음 | 그대로 보낸다 | — (표시 여부는 OS가 정한다) |

- **`PUT`은 멱등이다.** 같은 토큰을 몇 번 보내도 행은 하나다. 앱이 "이미 등록했는지"를 기억할 필요가 없으니 실행마다 무조건 부르는 편이 안전하다.
- 등록·해제는 **인증된 요청**이다. accessToken이 저장되기 전에 부르면 `401`이고, 서버는 재시도하지 않는다.
- `DELETE` 실패를 서버는 알 수 없다. 앱이 조용히 삼키면 토큰은 활성 상태로 남는다.

#### `POST /api/v1/notifications/{notificationId}/open`

- 최초 호출 시각을 `opened_at`으로 기록하고, 다시 불러도 첫 시각을 유지한다(`200`).
- 발송 상태는 보지 않는다. `PROCESSING`으로 남은 알림도 실제로 도착했을 수 있다.
- 없거나 내 알림이 아니면 `404 NOTIFICATION_NOT_FOUND`. `notificationId`가 숫자가 아니면 `400 INVALID_REQUEST_FORMAT`.
- 응답 `200`, `data: null`, 메시지 `알림 오픈이 기록되었습니다.`

#### 푸시 메시지

| 항목 | 값 |
|---|---|
| `notification.title` | 사용자가 붙인 시간대 이름(`label`) |
| `notification.body` | 식사 알림 본문(설정값 `notification.message.meal-body`) |
| `data.notificationId` | `push_log.id`의 **문자열** |
| `data.deepLink` | 설정값 `notification.push.deep-link` |
| 유효 시간 | 10분(Android `ttl`, iOS `apns-expiration`). 꺼져 있던 폰에 한참 지난 알림이 가지 않는다 |
| 우선순위 | Android `HIGH`, iOS `apns-priority: 10` |
| 사운드 | iOS `aps.sound: default`. Android는 채널 기본값 |

OS가 표시하는 알림이라 앱이 꺼져 있어도 뜬다. 기기가 여러 대면 모든 기기에 가고, 누른 기기의 기록만 남는다.

### 3.3. 발송 흐름

`NotificationSchedule`이 **매분 1초**(`Asia/Seoul`)에 `NotificationDispatchService.dispatch(now)`를 호출한다.

```text
① 이번 분    now → Asia/Seoul → 분 단위로 자름 → 요일 이름, "HH:mm", 그 분의 Instant
② 기록       SQL 한 문장(commit): 켜진 설정 × 요일·시각 일치 × 활성 토큰을 push_log(PROCESSING)로 넣고
             새로 들어간 행만 돌려받는다. 0건이면 끝
③ 발송       트랜잭션 밖에서 PushGateway.send → 메시지마다 결과
④ 결과 반영   SENT / FAILED 컬럼 지정 UPDATE, UNREGISTERED 토큰 비활성화
```

- **1초에 돌고 그냥 자르는 이유**: 스케줄러는 몇 ms 일찍 깨울 수 있다. 0초에 돌면 이전 분으로 잘려 그 분을 놓친다. 1초면 몇 ms 일찍 깨도, 59초까지 늦게 시작해도 같은 분이다.
- 같은 분에 두 번 돌거나 여러 프로세스가 동시에 돌아도 `UNIQUE(push_token_id, scheduled_at)` 때문에 두 번째는 0건이다.
- 한 설정에 같은 시각이 두 번 들어가 있어도 토큰당 1건만 기록한다(`distinct on`).
- 결과 반영은 컬럼 지정 UPDATE라, 발송 도중 먼저 기록된 `opened_at`을 덮지 않는다.

**FCM 결과 분류**

| FCM 결과 | `push_log.status` | 토큰 |
|---|---|---|
| 성공 | `SENT` | — |
| `UNREGISTERED` | `FAILED` | 비활성화 |
| 그 외 오류(`SENDER_ID_MISMATCH`·`INVALID_ARGUMENT` 포함) | `FAILED` | 유지 |
| 500건 묶음 호출 자체의 예외(자격증명 오류 포함) | 그 묶음 전체 `FAILED` | 유지 |

`SENDER_ID_MISMATCH`는 서버 설정 실수로도, `INVALID_ARGUMENT`는 메시지 버그로도 나서 토큰을 끄면 정상 토큰이 대량으로 꺼질 수 있다.

### 3.4. 트랜잭션과 동시성

| 작업 | 경계 | 동시성 |
|---|---|---|
| 설정 저장 | `@Transactional` 하나: `insert … on conflict (user_id) do nothing` → 조회 → Entity 갱신 | 첫 저장이 동시에 와도 500 없음. 행 잠금 없이 나중 커밋이 이긴다(전체 교체라 병합할 것이 없다) |
| 토큰 등록 | upsert 한 문장 | `on conflict (token)`이라 동시 등록도 row 하나 |
| 발송 | 서비스에 트랜잭션 없음. 기록·결과 반영은 각자 짧은 트랜잭션, FCM은 그 사이 트랜잭션 밖 | UNIQUE로 중복 기록 방지 |
| 오픈 기록 | UPDATE 한 문장(`coalesce(opened_at, now)`, `user_id` 조건) | 반복 호출에도 첫 시각 유지 |

### 3.5. 실행 구조와 설정

- 스케줄링은 `global/config/SchedulingConfig`에서 조건 없이 켠다. `@EnableScheduling`은 한 번 켜지면 컨텍스트의 모든 `@Scheduled`를 실행하므로, 실행 여부는 도메인별 `*Schedule` Bean이 자기 조건으로 등록될지로 정한다(Ingestion은 `IngestionSchedule`).
- **`notification.fcm.project-id`가 있는 프로세스가 알림 Worker를 맡는다.** 운영용 on/off 스위치는 없다. 테스트만 `notification.external.enabled=false`로 끄며 이때는 Worker도 맡지 않는다(Ingestion과 같다). Worker를 맡는데 본문이나 딥링크가 비어 있으면 기동이 실패한다.
- `FirebaseApp`은 첫 발송 때 ADC로 초기화한다. 발송하지 않는 프로세스와 ADC가 없는 로컬에서도 앱이 뜬다. connect·read·write timeout은 각 5초다.
- API와 같은 프로세스에서 돈다. 스케줄 스레드 풀은 3이다.

```yaml
notification:
  external:
    enabled: true                          # 테스트에서 false → FakePushGateway
  fcm:
    project-id: ${FCM_PROJECT_ID:}         # 비어 있으면 Worker를 맡지 않음
  message:
    meal-body: ${NOTIFICATION_MEAL_BODY:}
  push:
    deep-link: ${NOTIFICATION_DEEP_LINK:}
```

### 3.6. 로깅

| 상황 | 레벨 |
|---|---|
| Worker 기동 | INFO 1회 |
| 그 분에 보낸 알림 있음 | INFO 한 줄(대상·성공·실패·토큰 비활성 건수) |
| `UNREGISTERED` 외 실패 있음 | WARN 한 줄(오류 코드별 건수) |
| FCM 묶음 호출 예외, 발송 작업 자체 예외 | ERROR + stack |

토큰 원문, 시간대 이름, FCM 응답 원문은 남기지 않는다.

## 4. 받아들이는 한계

| 상황 | 결과 |
|---|---|
| 기록과 결과 반영 사이에 서버가 죽음 | `PROCESSING`으로 남고 다시 보내지 않음 |
| 대상 조회와 발송 사이 몇 초 동안 로그아웃·계정 전환 | 그 알림은 나감 |
| FCM 장애로 발송이 1분을 넘김 | 다음 분 작업이 밀려 그 분을 건너뛸 수 있음 |
| 서버가 그 분에 재기동 중 | 그 시각 알림 누락 |
| 두 기기에서 설정을 동시에 저장 | 나중 저장이 앞 저장을 덮음 |
| 로그아웃 중 `DELETE /push-tokens` 가 실패 | 그 기기 토큰이 활성으로 남아 이전 계정 알림이 간다. 다음 로그인 때 재귀속되거나 앱 삭제 시 FCM 이 정리한다 |

재시도가 필요해지면 `push_log`에 시도 횟수·다음 시도 시각과 `PENDING` 상태를 migration으로 더하고, Adapter 결과에 "재시도 가능"을 구분하고, `PENDING`을 가져가 보내는 짧은 폴링 작업을 둔다. row 단위 상태와 `PushGateway` 경계가 이미 있어 구조를 바꾸지 않고 덧붙일 수 있다.

## 5. 미정 사항

| 항목 | 결정 주체 | 막는 것 |
|---|---|---|
| 식사 알림 본문 문구 | 기획 | 없음. dev는 임시값으로 가동 중이며 확정되면 값만 교체한다 |
| 앱 딥링크 실제 주소 | FE | 없음. dev는 임시값으로 가동 중이며 확정되면 값만 교체한다 |
| 온보딩 칩별 기본 시각·요일 | 기획·FE | 서버 작업 없음(앱이 보유) |

~~로그아웃 시 서버가 토큰을 끌 것인가~~ **확정 완료(2026-09-21)** — 끄지 않는다. 앱의 `DELETE /push-tokens`와 재로그인 시 재귀속, 앱 삭제 시 FCM `UNREGISTERED` 비활성화로 충분하다. 로그아웃이 그 사용자 토큰을 전부 끄면 다른 기기의 알림까지 멈춘다.

~~탈퇴 방식과 Notification 정리~~ **확정 완료(2026-09-16)** — 하드 삭제로 정해졌고 `§2.3 도메인 협력`에 반영했다.
