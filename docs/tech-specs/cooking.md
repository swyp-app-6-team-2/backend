# Cooking Tech Spec

> **문서 버전**: v1 · **기준일**: 2026-09-07

## 한눈에 보기

Cooking은 사용자가 Recipe를 실제로 조리한 기록을 관리한다.

| 질문                   | 답변                                                                                   |
|----------------------|--------------------------------------------------------------------------------------|
| 무엇을 관리하는가?           | CookHistory, 조리 완료 시각, 완성 사진, 메모                                                     |
| 사용자는 무엇을 할 수 있는가?    | Recipe의 요리 완료 기록을 생성하고 해당 Recipe의 전체 이력을 조회                                          |
| 어떤 API를 제공하는가?       | 완료 기록 생성, Recipe별 이력 조회                                                              |
| 핵심 데이터는 무엇인가?        | CookHistory                                                                          |
| 어떤 도메인과 협력하는가?       | Recipe와 협력해 존재·소유권을 확인하고 삭제를 처리하며, [Upload](./upload.md)를 통해 사진 Key를 연결              |
| 핵심 기술 결정은 무엇인가?      | Recipe 존재·소유권을 사전 검증하고, `photoKey` 중복은 UNIQUE로 추가 방어. `recipe_id` FK는 migration 도구 도입 후로 미룸  |
| MVP에서 제외하거나 감수하는 것은? | 조리 진행 기능, 이력 단건·사용자 전체 조회와 개별 수정·삭제, Summary API와 Recipe 상세의 CookHistory 포함을 도입하지 않음 |

## 1. 개요

사용자는 Recipe를 조리한 뒤 CookHistory를 생성할 수 있다. 완성 사진과 메모는 선택적으로 함께 저장할 수 있다.

전체 흐름은 다음과 같다.

```text
Recipe → 조리 완료 → CookHistory
```

CookHistory의 생명주기는 사용자가 조리 완료 기록을 저장한 시점부터 시작된다.

## 2. 목표와 범위

### 2.1. 목표

Cooking 도메인은 MVP에서 다음 기능을 제공한다.

- 특정 Recipe의 요리 완료 기록 생성
    - 완료 시각은 서버가 기록하고 사진과 메모는 선택적으로 저장한다.
- 특정 Recipe의 요리 완료 이력 조회
- Recipe 삭제 시 연결된 CookHistory와 사진 정리

### 2.2. MVP 제외 범위

- 조리 시작·진행 상태, 타이머와 단계별 진행 체크
- CookHistory 단건 조회·수정·개별 삭제 및 사용자 전체 이력 조회
- Cooking Summary API와 Recipe 상세 응답의 CookHistory 포함

### 2.3. 도메인 협력

```text
Cooking ── Recipe 존재·소유권 확인 ──▶ Recipe
Recipe  ── 삭제 시 이력 정리 요청 ──▶ Cooking
Cooking ── 이미지 Key 연결·상태 관리 요청 ──▶ Upload
```

## 3. 기술 설계

### 3.1. 주요 처리 흐름

#### 요리 완료 기록 생성

1. 사용자가 특정 Recipe에 요리 완료 기록 생성을 요청한다.
2. Recipe의 존재 여부와 사용자 소유권을 확인한다.
3. 사진이 있으면 [Upload](./upload.md)를 통해 이미지 Key를 검증하고 연결한다.
4. 서버가 완료 시각을 기록하고 CookHistory를 저장한다.

#### Recipe별 요리 완료 이력 조회

1. Recipe의 존재 여부와 사용자 소유권을 확인한다.
2. CookHistory를 최근 조리 순으로 조회한다.
3. 사진이 있으면 접근 가능한 URL을 생성해 결과를 반환한다.

### 3.2. 데이터 모델

```text
Recipe 1 ── 0..N CookHistory
```

#### CookHistory

| 속성         | 필수 | 규칙                                      |
|------------|----|-----------------------------------------|
| `recipeId` | O  | 조리한 Recipe. FK 없는 스칼라이며 이유는 §3.4에 있다     |
| `cookedAt` | O  | 서버가 생성 요청 처리 시점에 기록                     |
| `photoKey` | X  | `COOK_HISTORY_PHOTO` 용도로 발급된 GCS 객체 Key |
| `memo`     | X  | 조리 완료 메모                                |

### 3.3. API 설계

| Method | Endpoint                                    | 기능             |
|--------|---------------------------------------------|----------------|
| `POST` | `/api/v1/recipes/{recipeId}/cook-histories` | CookHistory 생성 |
| `GET`  | `/api/v1/recipes/{recipeId}/cook-histories` | Recipe별 이력 조회  |

모든 API는 인증된 사용자만 호출할 수 있다. 사용자가 소유한 Recipe에 대해서만 생성·조회할 수 있으며, Recipe가 없거나 다른 사용자가 소유하면 `404 + RECIPE_NOT_FOUND`로 처리한다.

#### 생성

사진은 선택 사항이며, `photoKey` 상태에 따라 다음과 같이 처리한다.

- 전달하지 않음: 사진 없이 생성
- 정상적으로 업로드됐고 아직 연결되지 않음: 생성 트랜잭션에서 연결
- 유효하지 않음: `400 + COOK_HISTORY_PHOTO_INVALID`
- 이미 연결됨: `409 + COOK_HISTORY_PHOTO_ALREADY_USED`

성공하면 `201 Created`와 `data: null`을 반환한다. 생성된 CookHistory의 식별자를 응답에 담지 않는다. 단건 조회·수정·삭제 API가 없어 클라이언트가 식별자로 할 수 있는 일이 없기 때문이다.

`photoKey`와 `memo`가 모두 선택이므로 빈 객체 `{}`도 유효한 요청이다. 다만 요청 본문 자체가 없으면 `400 + INVALID_REQUEST_FORMAT`으로 거절한다.

전용 `Idempotency-Key`는 사용하지 않는다. 중복 입력은 클라이언트에서 방지하고 생성 POST는 자동 재시도하지 않는다.

#### 조회

사진은 `photoKey` 대신 `photoUrl`로 반환한다. 이력은 `cookedAt DESC, id DESC`로 정렬하며, 같은 조리 시각에는 내부 ID로 순서를 고정하되 ID 자체는 응답에 포함하지 않는다.

`cookedAt`은 ISO 8601 UTC(`Z`) datetime이다. 상대시간과 사용자 로컬 날짜 표시는 클라이언트가 계산한다.

이력이 없으면 빈 목록을 반환한다. MVP에서는 페이지네이션 없이 전체 이력을 조회한다.

### 3.4. 트랜잭션과 동시성 제어

#### CookHistory 생성

Recipe 존재와 소유권을 확인하고, 선택한 `photoKey` 연결과 CookHistory 저장을 하나의 DB 트랜잭션에서 처리한다. 저장이 실패하면 `photoKey` 연결을 포함한 전체 변경을 롤백한다.

생성 시 Recipe 행은 별도로 잠그지 않는다.

`UNIQUE(cook_history.photo_key)`는 같은 사진의 중복 연결을 막는 추가 방어선으로 사용한다. 해당 UNIQUE 위반만
`409 + COOK_HISTORY_PHOTO_ALREADY_USED`로 변환하며, 제약 이름으로 판별한다. 무결성 위반을 뭉뚱그려 변환하면 무관한 오류까지 409가 된다.

**`cook_history.recipe_id`에는 FK를 두지 않는다(2026-09-07 결정).** 도메인 경계 규칙상 Cooking은 Recipe Entity를 참조할 수 없고, 스칼라 컬럼에는 JPA가 FK를 만들지 않는다. FK는 migration 도구 도입(이슈 #11) 시점에 추가한다. `recipe.user_id`가 같은 이유로 FK 없는 스칼라다.

그 결과 Recipe 삭제와 CookHistory 생성이 경쟁하면 다음을 허용한다.

| 실행 순서                     | 결과                                                        |
|---------------------------|-----------------------------------------------------------|
| Recipe 삭제가 소유권 검증보다 먼저 완료 | CookHistory 생성 실패 → `404 + RECIPE_NOT_FOUND`              |
| Recipe 삭제가 소유권 검증과 저장 사이  | 저장 성공. 삭제된 Recipe를 가리키는 CookHistory·UploadObject·사진이 남는다  |
| CookHistory 생성이 먼저 완료     | 생성 성공 후 Recipe 삭제 시 해당 CookHistory도 함께 삭제 가능               |

두 번째 행이 FK를 두지 않아 생긴 빈틈이다. 사용자에게는 드러나지 않는다. 목록은 `recipeId`로 조회하고 그 Recipe는 이미 `404`이기 때문이다. 발생하려면 같은 사용자가 두 지점에서 동시에 조작해야 한다. FK가 들어오면 이 행은 첫 번째 행으로 흡수되고, 그때 FK 위반을 `404 + RECIPE_NOT_FOUND`로 변환한다.

#### 이력 조회

Recipe 존재·소유권 확인과 CookHistory 목록 조회는 각각 짧은 읽기 트랜잭션에서 끝내고, **조회 URL 서명은 트랜잭션 밖에서 수행한다.**

[Image Upload Common Spec](./upload.md) §3.4가 기록한 대로 서명에는 저장소 클라이언트 타임아웃이 걸리지 않는다. 이력 조회는 페이지네이션이 없어 N건을 순차로 서명하므로, 서명을 읽기 트랜잭션 안에 두면 요청 하나가 DB 커넥션을 그만큼 오래 점유한다. CookHistory에는 지연 로딩 컬렉션이 없어 트랜잭션 밖에서 응답을 조립해도 안전하다.

응답 시간 자체는 이력 건수에 비례해 늘어난다. 페이지네이션을 두지 않기로 한 결정의 대가로 감수한다.

#### Recipe 삭제

Recipe가 대상 Recipe 행을 잠그고 시작한 삭제 트랜잭션에 Cooking이 참여한다.

Cooking은 다음 순서로 관련 데이터를 정리한다.

1. CookHistory의 `photoKey`를 확보한다.
2. 해당 UploadObject를 제거한다.
3. CookHistory를 제거한다.

**Recipe가 호출하는 Cooking의 공개 UseCase는 CookHistory 생성·조회를 담당하는 Service와 다른 Bean이어야 한다.** 생성·조회 Service는 소유권 확인을 위해 이미 Recipe를 주입받고 있어, 같은 Bean에 삭제 정리까지 얹으면 두 Service가 서로를 생성자 주입해 애플리케이션이 기동하지 못한다. 정리 전용 Bean을 따로 두어 의존 방향을 한쪽으로 유지한다.
구현에서는 `CookHistoryCleanupService.deleteByRecipe(userId, recipeId)`가 이 UseCase다(2026-09-07).

커밋 후 삭제할 `photoKey`를 먼저 확보해야 하므로 CookHistory 삭제를
`ON DELETE CASCADE`에만 맡기지 않는다. 어느 단계에서든 실패하면 Recipe와 Cooking의 DB 변경을 모두 롤백한다.

DB 커밋 후 Cooking이 [Image Upload Common Spec](./upload.md)에 따라 GCS 사진 삭제를 시도한다. GCS 삭제 결과와 관계없이 Recipe DELETE API는
`200 OK`를 반환한다.

## 4. 주요 설계 결정과 선택 이유

### 4.1. 조리 완료 기록을 CookHistory로 분리

Recipe를 저장한 사실과 실제로 해당 Recipe를 조리한 사실은 서로 다른 생명주기를 가진다.

| 대안                      | 장점                                   | 단점                                  |
|-------------------------|--------------------------------------|-------------------------------------|
| Recipe에 조리 정보 저장        | 모델이 단순함                              | 여러 번의 조리 기록을 표현하기 어렵고 Recipe 책임이 커짐 |
| **CookHistory 분리 (채택)** | Recipe와 실제 조리 이력의 책임과 생명주기를 분리할 수 있음 | Recipe와의 조회 경계가 필요                  |

Recipe는 저장된 레시피를 관리하고 Cooking은 실제 조리 완료 이력을 관리한다.

### 4.2. CookHistory 생성에서 Recipe 행을 잠그지 않음

Recipe 검증 직후 Recipe가 삭제되는 동시성 경쟁을 막기 위해 모든 생성 요청에서 Recipe 행을 잠글지 결정해야 했다.

| 대안                           | 장점                             | 단점                                    |
|------------------------------|--------------------------------|---------------------------------------|
| CookHistory 생성마다 Recipe 행 잠금 | 삭제와 생성을 직렬화 가능                 | 일반 생성 요청에도 잠금 비용과 결합도가 증가             |
| 사전 검증 + FK                   | DB가 잘못된 관계 저장을 차단              | Cooking이 Recipe Entity를 참조해야 해 경계가 깨짐 |
| **사전 검증만 (채택)**              | 생성 흐름이 단순하고 도메인 경계를 그대로 유지     | 검증과 저장 사이의 경쟁에서 고아 데이터를 허용            |

CookHistory 생성에서는 Recipe 존재와 소유권을 확인하되 Recipe 행을 잠그지 않는다.

FK를 최종 방어선으로 쓰는 안은 도메인 경계 규칙과 충돌해 채택하지 못했다. migration 도구가 들어오면 Java 매핑과 무관하게 FK를 선언할 수 있으므로 그때 세 번째 대안이 두 번째로 수렴한다. 그 전까지 허용하는 빈틈은 §3.4에 적었다.

`photoKey` 중복은 사전 검증하고 UNIQUE 제약으로 추가 방어한다.
