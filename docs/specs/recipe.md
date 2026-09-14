# Recipe Tech Spec

> **문서 버전**: v1 · **기준일**: 2026-09-14

## 한눈에 보기

Recipe는 사용자가 최종 저장한 레시피와 그 출처를 관리한다.

| 질문                   | 답변                                                                            |
|----------------------|-------------------------------------------------------------------------------|
| 무엇을 관리하는가?           | Recipe, 재료, 조리 순서, 대표 이미지, 원본 출처                                              |
| 사용자는 무엇을 할 수 있는가?    | 직접 입력 또는 Ingestion 결과로 Recipe를 생성하고 목록·상세 조회·수정·삭제                            |
| 어떤 API를 제공하는가?       | 생성, 목록 조회, 상세 조회, 수정, 삭제                                                      |
| 핵심 데이터는 무엇인가?        | Recipe, RecipeIngredient, RecipeStep. 출처는 Recipe의 컬럼 3개                    |
| 어떤 도메인과 협력하는가?       | Ingestion·Cooking과 협력하고 [Upload](./upload.md)를 사용하며, Discovery에 Recipe 정보를 제공 |
| 핵심 기술 결정은 무엇인가?      | 동일 분석 결과의 중복 저장을 `ingestionJobId`·행 잠금·UNIQUE로 방지                             |
| MVP에서 제외하거나 감수하는 것은? | Ingredient·Step 독립 CRUD와 Recipe–Billing 저장 한도 연동을 도입하지 않음                     |

## 1. 개요

사용자는 레시피를 직접 입력하거나 URL·이미지 분석 결과를 확인하고 수정한 뒤 최종 Recipe로 저장할 수 있다.

전체 흐름은 다음과 같다.

```text
직접 입력 → Recipe
URL / 이미지 → Ingestion → 분석 초안(RecipeDraft) → 사용자 확인·수정 → Recipe
```

Recipe의 생명주기는 사용자가 최종 저장을 결정한 시점부터 시작된다.

## 2. 목표와 범위

### 2.1. 목표

Recipe 도메인은 MVP에서 다음 기능을 제공한다.

- 직접 입력 또는 Ingestion 결과를 이용한 Recipe 생성
- Recipe 목록 조회와 상세 조회·수정·삭제
- 재료·조리 순서와 생성 출처 보존
- 동일한 Ingestion 결과를 이용한 Recipe 중복 생성 방지
- 대표 이미지와 분석 원본 이미지의 연결·삭제 관리

### 2.2. MVP 제외 범위

- Ingredient와 Step의 독립적인 CRUD API
- Recipe 검색·필터
    - 검색·필터링은 Discovery 책임이다. 목록 조회는 Recipe가 제공하고, 조건을 거는 검색·필터는 Discovery가 담당한다.
- Recipe 저장 한도와 Billing 연동
    - Billing Tech Spec이 확정되기 전에는 임시 검증 Service나 `403` 계약을 두지 않는다.

### 2.3. 도메인 협력

```text
Recipe   ── 분석 작업 잠금·소비 요청 ──▶ Ingestion
Recipe   ── 정보 제공 ──▶ Discovery
Cooking  ── 존재·소유권 확인 ──▶ Recipe
Recipe   ── 삭제 시 이력 정리 요청 ──▶ Cooking
Recipe   ── 이미지 Key 연결·상태 관리 요청 ──▶ Upload
```

## 3. 기술 설계

### 3.1. 주요 처리 흐름

#### 직접 입력으로 생성

1. 사용자가 Recipe 정보를 입력한다.
2. Recipe와 RecipeIngredient, RecipeStep을 함께 저장한다.

#### Ingestion 결과로 생성

1. IngestionJob이 `RESULT_READY` 상태가 된다.
2. 사용자가 RecipeDraft를 확인하고 필요한 값을 수정한다.
3. 저장 요청이 IngestionJob을 사용 완료(소비) 처리한다.
4. 확정한 값으로 Recipe와 하위 데이터를 저장하고 원본 출처를 복사한다.

3과 4는 한 트랜잭션이다. 순서와 실패 처리는 `트랜잭션과 동시성 제어`가 정한다.

### 3.2. 데이터 모델

```text
Recipe 1 ── 0..N RecipeIngredient
Recipe 1 ── 0..N RecipeStep
```

**출처는 별도 테이블이 아니라 Recipe의 컬럼이다**(2026-09-12 결정). `RecipeSource`·`RecipeSourceImage` 테이블은 만들지 않는다. 등록 방식과 출처 컬럼의 조합은 DB CHECK로 강제한다 — `MANUAL`은 셋 다 없고, `URL`은 Job과 URL, `IMAGE`는 Job과 Key 1개 이상이다.

#### Recipe

| 속성                       | 필수 | 규칙                                                                     |
|--------------------------|----|------------------------------------------------------------------------|
| `userId`                 | O  | Access Token에서 식별한 소유자                                                 |
| `title`                  | O  | null 또는 빈 값 불가, 최대 255자                                                 |
| `categoryCode`           | O  | `KOREAN`, `WESTERN`, `CHINESE`, `JAPANESE`, `BUNSIK`, `ASIAN`, `OTHER` |
| `registrationMethod`     | O  | 직접 입력은 `MANUAL`, Ingestion은 Job의 입력 방식에 따라 `URL` 또는 `IMAGE` |
| `coverImageKey`          | X  | `RECIPE_COVER` 용도로 발급된 GCS 객체 Key                                      |
| `cookTimeMinutes`        | X  | 값이 있으면 1 이상의 정수                                                        |
| `servings`               | O  | 1 이상의 정수; 생성 요청에서 미전달 시 1                                              |
| `memo`                   | X  | 사용자 메모                                                                 |
| `createdAt`, `updatedAt` | O  | 생성·수정 시각                                                               |

#### RecipeIngredient

| 속성             | 필수 | 규칙                            |
|----------------|----|-------------------------------|
| `recipeId`     | O  | 소유 Recipe                     |
| `ingredientId` | X  | 공통 Ingredient와 연결할 때만 사용      |
| `name`         | O  | Recipe 저장 당시 사용자가 확인한 재료명 스냅샷. 최대 255자 |
| `amountText`   | X  | 수량과 단위를 포함한 원문 표현. 최대 255자    |
| `displayOrder` | O  | 요청 배열 순서를 기준으로 서버가 결정         |

#### RecipeStep

| 속성             | 필수 | 규칙                    |
|----------------|----|-----------------------|
| `recipeId`     | O  | 소유 Recipe             |
| `content`      | O  | null 또는 빈 값 불가        |
| `displayOrder` | O  | 요청 배열 순서를 기준으로 서버가 결정 |

#### Recipe 출처 컬럼

| 속성                | 필수 | 규칙                                                                 |
|-------------------|----|--------------------------------------------------------------------|
| `ingestionJobId`  | X  | 원본 IngestionJob. UNIQUE·FK. `MANUAL`이면 없음                          |
| `sourceUrl`       | X  | `URL` 방식에서만. 원본 URL을 스냅샷으로 보존                                     |
| `sourceImageKeys` | X  | `IMAGE` 방식에서만. `text[]` 배열이며 분석 원본을 복사하지 않고 기존 GCS 객체 Key를 순서대로 보존 |

플랫폼 값(`sourcePlatform`)은 저장하지 않는다. IngestionJob의 `sourceType`이 이미 갖고 있고, Recipe가 밖에 보여 주는 것은 `URL`·`IMAGE` 구분과 원본 URL뿐이다.

### 3.3. API 설계

| Method   | Endpoint                     | 기능        |
|----------|------------------------------|-----------|
| `POST`   | `/api/v1/recipes`            | Recipe 생성 |
| `GET`    | `/api/v1/recipes`            | Recipe 목록 조회 |
| `GET`    | `/api/v1/recipes/{recipeId}` | Recipe 상세 조회 |
| `PATCH`  | `/api/v1/recipes/{recipeId}` | Recipe 수정 |
| `DELETE` | `/api/v1/recipes/{recipeId}` | Recipe 삭제 |

모든 API는 인증된 사용자만 호출할 수 있다. 조회·수정·삭제는 사용자가 소유한 Recipe에만 허용하며, 존재하지 않거나 다른 사용자가 소유한 Recipe는
`404 + RECIPE_NOT_FOUND`로 처리한다.

#### 생성

직접 입력, URL, 이미지를 별도 Endpoint로 나누지 않고 하나의 생성 API로 처리한다.

- `ingestionJobId` 없음: `MANUAL`
- `ingestionJobId` 있음: Job의 입력 방식에 따라 `URL` 또는 `IMAGE`

`ingestionJobId`는 형식 검증을 걸지 않는다. 없거나 다른 사용자의 Job이면 `404 + INGESTION_JOB_NOT_FOUND`이고, 그 밖의 판정은 `트랜잭션과 동시성 제어`의 표가 정한다.

Recipe 내용은 사용자가 전달하고, 소유자·등록 방식·원본 출처·하위 데이터의 내부 ID와 표시 순서는 서버가 결정한다.

최초 생성은 Recipe ID와 `201 Created`를 반환한다. 동일한 IngestionJob으로 재요청하고 기존 Recipe가 있으면 새로 생성하지 않고 기존 Recipe ID와 `200 OK`를 반환한다. 이때 요청 내용(대표 이미지·재료 등)은 반영하지 않는다. 요청 형식 검증은 이 판단보다 먼저 돌므로, 본문이 잘못된 재요청은 `400`이다.

유효하지 않은 Cover Key는 `400 + RECIPE_COVER_INVALID`, 이미 연결된 Cover Key는 `409 + RECIPE_COVER_ALREADY_USED`로 처리한다.

`ingredients[].ingredientId`에 존재하지 않는 재료를 보내면 `400 + RECIPE_INGREDIENT_INVALID`로 요청 전체를 실패시킨다. 비활성 재료와 중복 사용은 허용한다 — 규칙과 근거는 [Ingredient Spec](./ingredient.md)의 `검증`과 `검증은 존재만, active는 보지 않음`이 소유한다.

#### 목록 조회

사용자가 소유한 Recipe를 페이지 단위로 반환한다. 조건을 거는 검색·필터는 제공하지 않는다(`MVP 제외 범위`).

| 파라미터 | 타입 | 필수 | 기본값 | 제약 |
|---------|------|-----|-------|------|
| `page` | int | X | `0` | `0` 이상 |
| `size` | int | X | `20` | `1` 이상 `100` 이하 |
| `sort` | enum | X | `LATEST` | `LATEST`, `OLDEST` |

정렬은 `LATEST`가 생성 시각 내림차순, `OLDEST`가 오름차순이다. 두 경우 모두 Recipe ID를 같은 방향의 동률 판정자로 함께 사용한다. 생성 시각이 같은 Recipe가 페이지 경계에 걸릴 때 중복되거나 누락되는 것을 막기 위함이다.

응답은 `totalCount`와 `recipes` 배열로 구성한다. `totalCount`는 반환한 건수가 아니라 조건에 해당하는 전체 결과 수다. 저장한 Recipe가 없거나 페이지가 범위를 벗어나면 `recipes`는 빈 배열이고 `totalCount`는 실제 전체 수를 유지한다.

배열의 각 항목은 목록 화면이 사용하는 다음 5개 필드만 포함한다.

- `recipeId`, `title`, `categoryCode`
- `coverImageUrl`: 대표 이미지의 조회 가능한 URL. 대표 이미지가 없거나 서명에 실패하면 `null`
- `ingredientNames`: 재료명 배열. 표시 순서를 따르며 재료가 없으면 `[]`

`memo`, `steps`, `source`, `cookTimeMinutes`, `servings`는 상세 조회 전용이며 목록에 포함하지 않는다. 조리 이력과 최근 조리 시각도 포함하지 않는다.

`page`가 음수이거나 `size`가 범위를 벗어나면 `400 + REQUEST_VALIDATION_FAILED`, `sort`에 정의되지 않은 값이나 숫자가 아닌 `page`를 전달하면 `400 + INVALID_REQUEST_FORMAT`으로 처리한다.

#### 상세 조회

응답에는 Recipe 기본 정보, Ingredient, Step과 출처를 포함한다. `source`는 `MANUAL`이면 `null`이고, 그 밖에는 `{sourceType, originalUrl}`이다. `sourceType`은 `URL` 또는 `IMAGE`이며 `IMAGE`의 `originalUrl`은 `null`이다. 대표 이미지는 조회 가능한 URL로 반환한다.

IMAGE 원본 목록, RecipeIngredient·RecipeStep의 PK와 표시 순서, CookHistory는 포함하지 않는다.

**`ingredients[].ingredientId`는 예외로 노출한다.** 이것은 내부 PK가 아니라 공통 Ingredient 마스터 참조이며, 수정이 재료 배열을 전체 교체하므로 앱이 다시 보낼 수 있어야 한다. 마스터에서 고르지 않은 재료는 `null`이다. 계약은 [Ingredient Spec](./ingredient.md)의 `Recipe Integration` 응답 절이 소유한다.

#### 수정

Recipe 기본 정보, Ingredient, Step과 대표 이미지를 수정할 수 있다. 등록 방식과 출처는 생성 이후 수정할 수 없다.

Ingredient와 Step은 개별 수정 API 없이 전체 교체한다.

- 배열 미전달: 기존 값 유지
- 빈 배열: 전체 삭제
- 값이 있는 배열: 기존 데이터를 전달된 값으로 교체

본문이 없거나 필드를 하나도 전달하지 않은 요청, 필수값(`title`·`categoryCode`·`servings`)이나 배열(`ingredients`·`steps`)에 명시적 `null`을 보낸 요청은 `400 + REQUEST_VALIDATION_FAILED`다.

`coverImageKey`는 미전달 시 유지하고,
`null`이면 대표 이미지를 제거한다. 새 Key를 전달하면 이미지를 교체하고, 기존 이미지의 GCS 삭제는 DB 커밋 이후 한 번 시도한다([Image Upload Common Spec](./upload.md)의 `주요 처리 흐름`). GCS 삭제가 실패해도 이미 완료된 Recipe 수정은 유지한다.

Cover Key 오류는 생성과 같은 `RECIPE_COVER_INVALID`, `RECIPE_COVER_ALREADY_USED` 계약을 사용한다. 재료 오류도 생성과 같은 `RECIPE_INGREDIENT_INVALID` 계약을 사용하며, 실패하면 기존 재료를 교체하지 않는다.

#### 삭제

논리 삭제 없이 영구 삭제(Hard Delete)한다. Recipe를 삭제하면 Ingredient, Step, 대표 이미지, 분석 원본 사진(`sourceImageKeys`)과 CookHistory도 함께 삭제한다. 원본 IngestionJob과 소비 기록은 남기므로 같은 Job으로 다시 저장할 수 없다.

GCS 삭제 시도까지 끝난 뒤 `200 OK`를 반환하며, GCS 삭제가 실패해도 Recipe 삭제 결과는 유지한다.

### 3.4. 트랜잭션과 동시성 제어

#### Recipe 생성

MANUAL 생성은 Recipe, RecipeIngredient, RecipeStep과 선택한 `coverImageKey` 연결을 하나의 DB 트랜잭션에서 처리한다.

Ingestion 기반 생성은 MANUAL 생성 범위에 다음 작업을 더해 같은 트랜잭션에서 처리한다.

1. IngestionJob을 비관적 쓰기 잠금(`SELECT ... FOR UPDATE`)하고 소유권을 확인한다.
2. 같은 `ingestionJobId`로 만든 Recipe가 이미 있으면 기존 Recipe ID와 `200 OK`를 반환하고 종료한다.
3. Job의 `consumedAt`, 만료 여부, 상태를 이 순서로 확인하고, 통과하면 `consumedAt`을 설정하고 임시 `result`와 `previewImageUrl`을 제거한다.
4. 출처를 복사해 Recipe, RecipeIngredient, RecipeStep을 저장하고 선택한 `coverImageKey`를 연결한다. IMAGE 방식이면 GCS 객체를 복사하지 않고 IngestionJob의 `inputImageKeys`를 `sourceImageKeys`로 순서 그대로 복사한다. 원본 Job의 Key는 지우지 않는다.

Recipe는 Ingestion의 Repository를 보지 않고, Ingestion이 공개한 잠금·소비 경계만 부른다. 이 경계는 호출자의 트랜잭션 안에서만 부를 수 있다 — 트랜잭션 밖에서 부르면 잠금이 즉시 풀려 직렬화가 깨지기 때문이다. 대표 이미지 존재 확인(원격 호출)은 Job 행 잠금을 쥔 채 일어나며, 같은 Job의 연타 요청만 그만큼 기다린다.

어느 단계에서든 실패하면 Recipe·Upload·Ingestion 변경을 모두 롤백한다.

동일한 `ingestionJobId` 요청은 IngestionJob 행 잠금으로 직렬화한다. Recipe 생성과
`RESULT_READY → EXPIRED` 전이도 같은 행을 잠가 소비와 만료가 동시에 처리되지 않게 한다.

DB에는 `recipe.ingestion_job_id` UNIQUE 제약을 두어 같은 Job으로 Recipe가 두 번 생기는 것을 추가로 막는다.

별도 멱등 키는 사용하지 않고 `ingestionJobId`를 자연 멱등 키로 사용한다. Recipe가 삭제돼도 IngestionJob의 `consumedAt`은 유지하여 같은 분석 결과의 재사용을 차단한다.

동일한 IngestionJob 요청은 다음과 같이 처리한다.

| 조건                                                               | 결과                                     |
|------------------------------------------------------------------|----------------------------------------|
| Job이 없거나 다른 사용자가 소유함                                             | `404 + INGESTION_JOB_NOT_FOUND`        |
| 같은 Job으로 만든 Recipe가 이미 있음                                        | 기존 Recipe ID와 `200 OK`                 |
| 그런 Recipe가 없고 `consumedAt`도 없음, Job이 사용 가능한 `RESULT_READY`       | 최초 생성 후 `201 Created`                  |
| 그런 Recipe가 없고 `consumedAt`이 존재함                                  | `409 + INGESTION_JOB_ALREADY_CONSUMED` |
| Job이 만료됨                                                         | `409 + INGESTION_JOB_EXPIRED`          |
| Job이 만료 전이지만 `RESULT_READY`가 아님                                  | `409 + INGESTION_JOB_INVALID_STATE`    |

#### Recipe 목록 조회

목록 조회는 트랜잭션을 열지 않는다. Recipe 페이지 조회와 재료명 조회를 각각 짧은 읽기 트랜잭션에서 끝내고, 대표 이미지의 조회 URL 서명은 DB 커넥션을 쥐지 않은 상태에서 수행한다.

서명을 트랜잭션 밖으로 분리하는 이유는 서명에 저장소 클라이언트 타임아웃이 적용되지 않고([Image Upload Common Spec](./upload.md)의 `트랜잭션과 원격 호출 경계`), **페이지 크기가 곧 외부 호출 횟수**이기 때문이다. 커넥션을 점유한 채 최대 `size`회의 외부 호출을 수행하지 않는다.

재료명은 Recipe Entity의 지연 로딩 컬렉션이 아니라 Recipe ID 목록으로 한 번에 조회한다. 페이지의 Recipe마다 컬렉션을 조회하면 N+1이 되고, 트랜잭션 밖에서는 지연 로딩 자체가 불가능하다.

두 읽기 트랜잭션 사이에 Recipe가 삭제되면 해당 Recipe의 재료명이 빈 배열로 반환될 수 있다. 같은 사용자가 두 지점에서 동시에 조작해야 발생하고 사용자에게 드러나는 피해가 없어 MVP에서 허용한다.

#### Recipe 수정·삭제

수정과 삭제는 대상 Recipe 행을 비관적 쓰기 잠금하여 직렬화한다.

수정 시 Recipe 기본 정보, RecipeIngredient와 RecipeStep 변경을 하나의 DB 트랜잭션에서 처리한다. 대표 이미지가 현재와 같은 Key면 이미지 작업을 생략하고, 다른 Key면 새 UploadObject 연결과 기존 UploadObject 제거도 같은 트랜잭션에서 처리한다. 기존 파일의 GCS 삭제는 트랜잭션 안이 아니라 커밋 이후에 한 번 시도한다.
`null`이면 새 Key 연결 없이 기존 이미지 참조와 UploadObject만 제거한다.

삭제는 같은 DB 트랜잭션에서 다음 순서로 처리한다.

1. 대표 이미지와 원본 이미지 Key를 확보하고 해당 UploadObject를 제거한다.
   원본 이미지는 Recipe로 넘어온 뒤에도 `INGESTION_INPUT` 용도로 해제한다.
2. Cooking에 관련 CookHistory 정리를 요청한다.
3. Recipe가 소유한 하위 데이터와 Recipe를 제거한다.
4. 모든 DB 변경을 커밋한다.

DB 처리 중 실패하면 전체 변경을 롤백한다. 커밋 후 Recipe는 대표·원본 이미지를, Cooking은 CookHistory 사진을 [Image Upload Common Spec](./upload.md)에 따라 GCS에서 삭제한다.

GCS 삭제가 실패해도 커밋된 DB 변경과 API 성공 응답은 유지한다.

## 4. 주요 설계 결정과 선택 이유

### 4.1. 분석 결과를 바로 Recipe로 저장하지 않음

URL이나 이미지 분석 결과가 항상 최종 Recipe로 저장되는 것은 아니다. 사용자가 결과를 수정하거나 저장하지 않을 수도 있어 분석 과정과 최종 Recipe의 생명주기를 분리해야 한다.

| 대안                                 | 장점                               | 단점                                                   |
|------------------------------------|----------------------------------|------------------------------------------------------|
| 분석 완료 즉시 Recipe 생성                 | 별도의 중간 모델 없이 흐름이 단순함             | 사용자가 저장하지 않은 결과까지 Recipe로 남고 분석 상태와 최종 Recipe 상태가 섞임 |
| 별도 Draft 저장 모델 운영                  | 자동 저장, 이어쓰기 등 Draft 기능 확장에 유리    | Draft 상태, 수정 API, 만료 정책 등 추가 복잡도 발생                  |
| **Ingestion과 Recipe 생명주기 분리 (채택)** | 분석 상태와 최종 사용자 데이터를 명확하게 분리할 수 있음 | 최종 저장 시 Ingestion → Recipe 전환 과정이 필요                 |

현재 MVP에서는 서버 Draft 자동 저장이나 이어쓰기 요구가 없으므로, 별도 Draft 저장 모델을 추가하지 않고 분석 결과와 최종 사용자 데이터의 생명주기를 분리했다.

### 4.2. `ingestionJobId`를 자연 멱등 키로 활용

네트워크 재시도나 중복 요청으로 동일한 Ingestion 결과에서 Recipe가 여러 개 생성될 수 있기 때문에 중복 생성 방지 전략이 필요했다.

| 대안                                        | 장점                             | 단점                          |
|-------------------------------------------|--------------------------------|-----------------------------|
| 별도 `Idempotency-Key` 도입                   | 다양한 생성 API에서 범용적으로 사용할 수 있음    | Key 생성, 저장, 만료 정책이 추가로 필요   |
| 생성 전 기존 Recipe만 조회                        | 구현이 단순함                        | 동시 요청이 같은 시점에 조회하면 중복 생성 가능 |
| **`ingestionJobId` + 행 잠금 + UNIQUE (채택)** | 기존 비즈니스 식별자를 활용하면서 동시성까지 제어 가능 | Ingestion 기반 생성에 특화된 방식     |

별도의 범용 멱등 시스템 대신 `ingestionJobId`, 행 잠금과 UNIQUE 제약을 조합해 Ingestion 기반 생성의 중복을 방지한다.

### 4.3. 원본 출처를 Recipe 컬럼으로 보존

URL이나 이미지 기반 Recipe는 분석 결과를 사용자가 수정한 뒤 저장하므로, 최종 데이터만으로는 어떤 원본에서 만들어졌는지 확인하기 어렵다.

| 대안                          | 장점                               | 단점                                                          |
|-----------------------------|----------------------------------|-------------------------------------------------------------|
| Recipe가 IngestionJob만 계속 참조 | 중복 데이터 저장을 줄일 수 있음               | Recipe의 장기 보존 정책이 Ingestion의 임시 데이터 생명주기에 의존                |
| RecipeSource 별도 모델           | Recipe 내용과 출처의 책임·생명주기를 분리할 수 있음 | Source Entity와 자식 테이블이 추가됨                                  |
| **Recipe에 출처 컬럼 3개 추가 (채택, 2026-09-12)** | 조회 구조가 단순하고 테이블 두 개를 만들지 않음 | MANUAL Recipe에 nullable 컬럼 3개. 출처 형태가 늘면 테이블로 분리 |

처음에는 `RecipeSource`를 별도 모델로 두기로 했으나, **출처가 컬럼 세 개(`ingestionJobId`·`sourceUrl`·`sourceImageKeys`)에 그치는 범위에서는 테이블 두 개를 만드는 비용이 더 크다고 보고 2026-09-12에 뒤집었다.** 최종 Recipe가 Ingestion의 임시 데이터 생명주기와 독립적으로 원본 출처를 보존한다는 목적은 그대로다 — Key를 복사해 두므로 Job이 정리돼도 Recipe는 출처를 잃지 않는다.
