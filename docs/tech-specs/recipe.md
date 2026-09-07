# Recipe Tech Spec

> **문서 버전**: v1 · **기준일**: 2026-09-07

## 한눈에 보기

Recipe는 사용자가 최종 저장한 레시피와 그 출처를 관리한다.

| 질문                   | 답변                                                                            |
|----------------------|-------------------------------------------------------------------------------|
| 무엇을 관리하는가?           | Recipe, 재료, 조리 순서, 대표 이미지, 원본 출처                                              |
| 사용자는 무엇을 할 수 있는가?    | 직접 입력 또는 Ingestion 결과로 Recipe를 생성하고 상세 조회·수정·삭제                               |
| 어떤 API를 제공하는가?       | 생성, 상세 조회, 수정, 삭제                                                             |
| 핵심 데이터는 무엇인가?        | Recipe, RecipeIngredient, RecipeStep, RecipeSource, RecipeSourceImage         |
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
- Recipe 상세 조회·수정·삭제
- 재료·조리 순서와 생성 출처 보존
- 동일한 Ingestion 결과를 이용한 Recipe 중복 생성 방지
- 대표 이미지와 분석 원본 이미지의 연결·삭제 관리

### 2.2. MVP 제외 범위

- Ingredient와 Step의 독립적인 CRUD API
- Recipe 저장 한도와 Billing 연동
    - Billing Tech Spec이 확정되기 전에는 임시 검증 Service나 `403` 계약을 두지 않는다.

### 2.3. 도메인 협력

```text
Ingestion ── 분석 결과 제공 ──▶ Recipe ── 정보 제공 ──▶ Discovery
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
3. 확정한 값으로 Recipe와 하위 데이터를 저장하고 원본 출처를 이전한다.
4. IngestionJob을 사용 완료 처리한다.

### 3.2. 데이터 모델

```text
Recipe 1 ── 0..N RecipeIngredient
Recipe 1 ── 0..N RecipeStep
Recipe 1 ── 0..1 RecipeSource
RecipeSource 1 ── 0..N RecipeSourceImage
```

#### Recipe

| 속성                       | 필수 | 규칙                                                                     |
|--------------------------|----|------------------------------------------------------------------------|
| `userId`                 | O  | Access Token에서 식별한 소유자                                                 |
| `title`                  | O  | null 또는 빈 값 불가                                                         |
| `categoryCode`           | O  | `KOREAN`, `WESTERN`, `CHINESE`, `JAPANESE`, `BUNSIK`, `ASIAN`, `OTHER` |
| `registrationMethod`     | O  | 직접 입력은 `MANUAL`, Ingestion은 Job의 입력 방식에 따라 `URL` 또는 `IMAGE`            |
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
| `name`         | O  | Recipe 저장 당시 사용자가 확인한 재료명 스냅샷 |
| `amountText`   | X  | 수량과 단위를 포함한 원문 표현             |
| `displayOrder` | O  | 요청 배열 순서를 기준으로 서버가 결정         |

#### RecipeStep

| 속성             | 필수 | 규칙                    |
|----------------|----|-----------------------|
| `recipeId`     | O  | 소유 Recipe             |
| `content`      | O  | null 또는 빈 값 불가        |
| `displayOrder` | O  | 요청 배열 순서를 기준으로 서버가 결정 |

#### RecipeSource

| 속성               | 필수 | 규칙                                                     |
|------------------|----|--------------------------------------------------------|
| `recipeId`       | O  | 소유 Recipe; `MANUAL`에는 생성하지 않고 `URL` 또는 `IMAGE`에는 1개 생성 |
| `ingestionJobId` | O  | 원본 IngestionJob; Job당 최대 1개                            |
| `sourcePlatform` | X  | `URL` 방식에서는 필수이며 `YOUTUBE` 또는 `INSTAGRAM`; `IMAGE`는 없음 |
| `originalUrl`    | X  | `URL` 방식에서는 필수이며 원본 URL을 스냅샷으로 보존; `IMAGE`는 없음         |

#### RecipeSourceImage

| 속성               | 필수 | 규칙                                         |
|------------------|----|--------------------------------------------|
| `recipeSourceId` | O  | 소유 RecipeSource; `IMAGE` Source에는 1개 이상 생성 |
| `objectKey`      | O  | 분석 원본을 복사하지 않고 기존 GCS 객체 Key를 보존           |
| `displayOrder`   | O  | 원본 이미지 순서이며 동일 Source 안에서 중복 불가            |

### 3.3. API 설계

| Method   | Endpoint                     | 기능        |
|----------|------------------------------|-----------|
| `POST`   | `/api/v1/recipes`            | Recipe 생성 |
| `GET`    | `/api/v1/recipes/{recipeId}` | Recipe 조회 |
| `PATCH`  | `/api/v1/recipes/{recipeId}` | Recipe 수정 |
| `DELETE` | `/api/v1/recipes/{recipeId}` | Recipe 삭제 |

모든 API는 인증된 사용자만 호출할 수 있다. 조회·수정·삭제는 사용자가 소유한 Recipe에만 허용하며, 존재하지 않거나 다른 사용자가 소유한 Recipe는
`404 + RECIPE_NOT_FOUND`로 처리한다.

#### 생성

직접 입력, URL, 이미지를 별도 Endpoint로 나누지 않고 하나의 생성 API로 처리한다.

- `ingestionJobId` 없음: `MANUAL`
- `ingestionJobId` 있음: Job의 입력 방식에 따라 `URL` 또는 `IMAGE`

Recipe 내용은 사용자가 전달하고, 소유자·등록 방식·원본 출처·하위 데이터의 내부 ID와 표시 순서는 서버가 결정한다.

최초 생성은 Recipe ID와 `201 Created`를 반환한다. 동일한 IngestionJob으로 재요청하고 기존 Recipe가 있으면 새로 생성하지 않고 기존 Recipe ID와 `200 OK`를 반환한다.

유효하지 않은 Cover Key는 `400 + RECIPE_COVER_INVALID`, 이미 연결된 Cover Key는 `409 + RECIPE_COVER_ALREADY_USED`로 처리한다.

#### 조회

응답에는 Recipe 기본 정보, Ingredient, Step과 RecipeSource를 포함한다. 대표 이미지는 조회 가능한 URL로 반환한다.

IMAGE 원본 목록, Ingredient와 Step의 내부 ID·표시 순서, CookHistory는 포함하지 않는다.

#### 수정

Recipe 기본 정보, Ingredient, Step과 대표 이미지를 수정할 수 있다. 등록 방식과 RecipeSource는 생성 이후 수정할 수 없다.

Ingredient와 Step은 개별 수정 API 없이 전체 교체한다.

- 배열 미전달: 기존 값 유지
- 빈 배열: 전체 삭제
- 값이 있는 배열: 기존 데이터를 전달된 값으로 교체

`coverImageKey`는 미전달 시 유지하고,
`null`이면 대표 이미지를 제거한다. 새 Key를 전달하면 이미지를 교체하고 기존 이미지를 정리한다. GCS 삭제가 실패해도 이미 완료된 Recipe 수정은 유지한다.

Cover Key 오류는 생성과 같은 `RECIPE_COVER_INVALID`, `RECIPE_COVER_ALREADY_USED` 계약을 사용한다.

#### 삭제

논리 삭제 없이 영구 삭제(Hard Delete)한다. Recipe를 삭제하면 Ingredient, Step, RecipeSource, RecipeSourceImage, 대표·원본 이미지와 CookHistory도 함께 삭제한다.

GCS 삭제 시도까지 끝난 뒤 `200 OK`를 반환하며, GCS 삭제가 실패해도 Recipe 삭제 결과는 유지한다.

### 3.4. 트랜잭션과 동시성 제어

#### Recipe 생성

MANUAL 생성은 Recipe, RecipeIngredient, RecipeStep과 선택한 `coverImageKey` 연결을 하나의 DB 트랜잭션에서 처리한다.

Ingestion 기반 생성은 MANUAL 생성 범위에 다음 작업을 더해 같은 트랜잭션에서 처리한다.

1. IngestionJob을 비관적 쓰기 잠금(`SELECT ... FOR UPDATE`)하고 소유권을 확인한다.
2. 기존 RecipeSource가 있으면 기존 Recipe ID와 `200 OK`를 반환하고 종료한다.
3. Job의 상태, 만료 여부와 `consumedAt`을 확인한다.
4. 선택한 `coverImageKey`를 연결하고 Recipe, RecipeIngredient, RecipeStep과 RecipeSource를 저장한다.
5. IMAGE 방식이면 GCS 객체를 복사하지 않고 RecipeSourceImage를 저장한 뒤 IngestionJobImage를 제거한다.
6. `consumedAt`을 설정하고 임시 `result`를 제거한다.

어느 단계에서든 실패하면 Recipe·Upload·Ingestion 변경을 모두 롤백한다.

동일한 `ingestionJobId` 요청은 IngestionJob 행 잠금으로 직렬화한다. Recipe 생성과
`RESULT_READY → EXPIRED` 전이도 같은 행을 잠가 소비와 만료가 동시에 처리되지 않게 한다.

DB에는 `UNIQUE(recipe_source.ingestion_job_id)`와 `UNIQUE(recipe_source.recipe_id)`를 두어 중복 관계를 추가로 방지한다.

별도 멱등 키는 사용하지 않고 `ingestionJobId`를 자연 멱등 키로 사용한다. Recipe가 삭제돼도 IngestionJob의 `consumedAt`은 유지하여 같은 분석 결과의 재사용을 차단한다.

동일한 IngestionJob 요청은 다음과 같이 처리한다.

| 조건                                                               | 결과                                     |
|------------------------------------------------------------------|----------------------------------------|
| Job이 없거나 다른 사용자가 소유함                                             | `404 + INGESTION_JOB_NOT_FOUND`        |
| 기존 RecipeSource가 존재함                                             | 기존 Recipe ID와 `200 OK`                 |
| 기존 RecipeSource가 없고 `consumedAt`도 없음, Job이 사용 가능한 `RESULT_READY` | 최초 생성 후 `201 Created`                  |
| 기존 RecipeSource가 없고 `consumedAt`이 존재함                            | `409 + INGESTION_JOB_ALREADY_CONSUMED` |
| Job이 만료됨                                                         | `409 + INGESTION_JOB_EXPIRED`          |
| Job이 만료 전이지만 `RESULT_READY`가 아님                                  | `409 + INGESTION_JOB_INVALID_STATE`    |

#### Recipe 수정·삭제

수정과 삭제는 대상 Recipe 행을 비관적 쓰기 잠금하여 직렬화한다.

수정 시 Recipe 기본 정보, RecipeIngredient와 RecipeStep 변경을 하나의 DB 트랜잭션에서 처리한다. 대표 이미지가 현재와 같은 Key면 이미지 작업을 생략하고, 다른 Key면 새 UploadObject 연결과 기존 UploadObject 제거도 같은 트랜잭션에서 처리한다.
`null`이면 새 Key 연결 없이 기존 이미지 참조와 UploadObject만 제거한다.

삭제는 같은 DB 트랜잭션에서 다음 순서로 처리한다.

1. 대표 이미지와 원본 이미지 Key를 확보하고 해당 UploadObject를 제거한다.
   현재 구현은 대표 이미지만 처리한다. RecipeSource와 RecipeSourceImage는 Ingestion 단계에서
   Entity가 만들어질 때 이 자리에 함께 들어온다.
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

### 4.3. 원본 출처를 RecipeSource로 분리

URL이나 이미지 기반 Recipe는 분석 결과를 사용자가 수정한 뒤 저장하므로, 최종 데이터만으로는 어떤 원본에서 만들어졌는지 확인하기 어렵다.

| 대안                          | 장점                               | 단점                                                          |
|-----------------------------|----------------------------------|-------------------------------------------------------------|
| Recipe에 원본 필드를 직접 추가        | 조회 구조가 단순함                       | MANUAL Recipe에 불필요한 nullable 필드가 늘고 출처 형태가 늘수록 Recipe가 비대해짐 |
| Recipe가 IngestionJob만 계속 참조 | 중복 데이터 저장을 줄일 수 있음               | Recipe의 장기 보존 정책이 Ingestion의 임시 데이터 생명주기에 의존                |
| **RecipeSource 별도 모델 (채택)** | Recipe 내용과 출처의 책임·생명주기를 분리할 수 있음 | Source Entity가 추가됨                                          |

RecipeSource를 별도 모델로 두어 최종 Recipe가 Ingestion의 임시 데이터 생명주기와 독립적으로 원본 출처를 보존하도록 했다.
