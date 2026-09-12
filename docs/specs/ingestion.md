# Ingestion Tech Spec

> **문서 버전**: v1 · **기준일**: 2026-09-12 · **상태**: 초안(승인 전)

## 한눈에 보기

Ingestion은 사용자가 보낸 사진이나 링크를 분석해 레시피 초안(RecipeDraft)을 만든다.

| 질문                   | 답변                                                                                                   |
|----------------------|------------------------------------------------------------------------------------------------------|
| 무엇을 관리하는가?           | IngestionJob(분석 요청·처리 상태·결과)                                                                        |
| 사용자는 무엇을 할 수 있는가?    | 사진·YouTube·Instagram으로 분석을 요청하고, 결과를 "레시피 직접 입력" 폼에 채워 Recipe로 저장                                   |
| 어떤 API를 제공하는가?       | 분석 요청, 분석 상태 조회                                                                                      |
| 핵심 데이터는 무엇인가?        | IngestionJob                                                                                         |
| 어떤 도메인과 협력하는가?       | [Recipe](./recipe.md)에 분석 결과를 제공하고, [Upload](./upload.md)로 입력 사진을 연결하며, [Ingredient](./ingredient.md)로 재료를 매칭 |
| 핵심 기술 결정은 무엇인가?      | 요청은 202로 받고, 같은 프로세스의 Worker가 PostgreSQL Job 테이블을 2초마다 `SKIP LOCKED`로 가져가 처리. Gemini는 REST로 직접 부르고 사진은 서버가 읽어 요청에 넣음 |
| MVP에서 제외하거나 감수하는 것은? | 웹페이지 URL·결과 재사용·완료 푸시를 두지 않음. 처리 시작 최대 2초 지연과 재배포 시 최대 약 3분 지연을 감수                                |

## 1. 개요

사용자는 사진을 올리거나 링크를 붙여 넣어 분석을 요청한다. 분석은 수 초에서 수십 초 걸리므로 서버는 요청을 먼저 접수하고, 앱은 결과가 나올 때까지 상태를 반복 조회한다.

```text
사진 / YouTube / Instagram → 분석 요청(202) → Worker가 Gemini로 분석 → RecipeDraft
  → 앱이 "레시피 직접 입력" 폼에 채움 → 사용자가 수정 → Recipe 저장
```

IngestionJob의 생명주기는 다음과 같다.

- 분석 요청 시점에 생긴다.
- 결과는 24시간 뒤 만료된다.
- Recipe로 저장된 Job은 영구 보존한다. 같은 결과로 Recipe가 두 번 생기지 않게 막는 기록이기 때문이다.
- 저장되지 않은 실패·만료 Job은 7일 뒤 지운다.

### 1.1. 용어

| 용어         | 뜻                                                                   |
|------------|---------------------------------------------------------------------|
| Job        | 분석 요청 하나. `ingestion_job` 테이블의 한 행                                  |
| Worker     | Job을 가져가 분석하는 백그라운드 실행기. API와 같은 애플리케이션 안에서 돈다                      |
| 선점         | Worker가 대기 중인 Job을 "처리 중"으로 바꾸며 가져가는 것                               |
| stale      | 처리 중으로 표시됐는데 너무 오래 끝나지 않은 Job. 서버가 중간에 죽은 것으로 본다                     |
| 소비         | Job의 결과로 Recipe를 저장한 것. 소비된 Job은 다시 쓸 수 없다                          |
| RecipeDraft | 서버가 정리한 분석 결과. Recipe 생성 요청과 같은 모양이다                               |

## 2. 목표와 범위

### 2.1. 목표

- 사진(순서 있는 1장 이상), YouTube(일반·Shorts), Instagram(게시물·carousel·Reel)에서 RecipeDraft 하나를 만든다.
- 분석 결과를 기존 Recipe 생성 API로 저장하고, Job 하나에서 Recipe는 하나만 생기게 한다.
- 사용자별 일일 한도로 Gemini 비용 폭주를 막는다.

### 2.2. MVP 제외 범위

- **일반 웹페이지 URL**(네이버 블로그, 티스토리 등). 입력 종류 하나와 입력 조립 분기 하나로 붙일 수 있는 구조만 둔다.
- 같은 URL의 분석 결과 재사용
- 진행률, WebSocket/SSE, 완료 푸시. 상태 조회(polling) 계약을 유지한다.
- Job 재시도·취소·목록 API. 다시 시도는 새 Job을 만든다.
- Worker 별도 프로세스, 메시지 브로커(§4.1, §4.2)
- carousel의 영상 카드 분석
- 업로드 사진의 서버 크기 제한. 앱이 줄여서 올린다(§3.3).

### 2.3. 구현 단계

| 단계 | 범위                                              |
|----|-------------------------------------------------|
| 1  | 사진 입력, 분석 요청·조회 API, Worker, Gemini 연동, 주기 작업   |
| 2  | Recipe 저장 연동, YouTube                            |
| 3  | Instagram. 비공식 수집 수용(팀)과 GCE IP 접근 실측이 끝난 뒤 착수 |

1단계만 머지된 동안 앱은 분석 결과를 직접 입력(`MANUAL`)으로만 저장할 수 있다.

### 2.4. 도메인 협력

```text
Recipe    ── Job 잠금·소비 ──▶ Ingestion
Ingestion ── 입력 사진 연결·읽기·조회 URL·해제 ──▶ Upload
Ingestion ── 활성 재료 조회 ──▶ Ingredient
Ingestion ── 분석 ──▶ Gemini
Ingestion ── 게시물 수집 ──▶ Instagram (3단계)
```

Ingestion은 Recipe를 알지 못한다. 호출은 항상 Recipe에서 Ingestion으로만 향한다.

## 3. 기술 설계

### 3.1. 주요 처리 흐름

#### 분석 요청

1. 입력 조합·개수·중복을 검사한다.
2. URL이면 지원하는 형식인지 판별한다.
3. 일일 한도를 확인한다.
4. 사진이면 [Upload](./upload.md)를 통해 Key를 하나씩 연결한다.
5. Job을 `QUEUED`로 저장하고 `202 Accepted`를 반환한다.

#### 분석 처리 (Worker)

1. 2초마다 비어 있는 실행 슬롯 수만큼 `QUEUED` Job을 선점해 `PROCESSING`으로 바꾼다.
2. 선점한 시도가 아직 유효한지 확인한다. 아니면 외부 호출 없이 끝낸다.
3. 입력을 조립한다.
   - 사진: 크기를 먼저 확인하고, GCS에서 읽어 순서대로 요청에 넣는다.
   - YouTube: URL을 넘긴다.
   - Instagram: §3.5
4. Gemini로 분석한다.
5. 결과를 정규화하고 재료를 매칭한다.
6. 결과를 `RESULT_READY`로 저장하거나, 실패 이유와 함께 `FAILED`로 저장한다.

#### 결과 조회

앱은 1~2초 간격으로 상태를 조회하고 `RESULT_READY`, `FAILED`, `EXPIRED`에서 멈춘다. 결과가 준비되면 "레시피 직접 입력" 폼에 값을 채운다.

#### Recipe로 저장 (2단계)

사용자가 폼에서 값을 고치고 저장하면, 기존 Recipe 생성 API에 `ingestionJobId`를 함께 보낸다. Recipe가 Job을 소비 처리하고 출처를 복사한다(§3.4).

### 3.2. 데이터 모델

```text
User 1 ── 0..N IngestionJob
IngestionJob 1 ── 0..1 Recipe   (2단계)
```

#### IngestionJob

| 속성                | 필수 | 규칙                                                                                  |
|-------------------|----|-------------------------------------------------------------------------------------|
| `userId`          | O  | 요청한 사용자                                                                             |
| `sourceType`      | O  | `IMAGE`, `YOUTUBE`, `INSTAGRAM`. API의 `inputType`은 여기서 계산한다(`IMAGE` → `IMAGE`, 나머지 → `URL`) |
| `inputUrl`        | X  | URL 입력일 때만. 정규화한 URL이며 Instagram 게시물은 `img_index`를 보존한다                              |
| `inputImageKeys`  | X  | 사진 입력일 때만, 1개 이상. `INGESTION_INPUT` 용도 Key이며 배열 순서가 분석 순서다                          |
| `status`          | O  | `QUEUED`, `PROCESSING`, `RESULT_READY`, `FAILED`, `EXPIRED`                         |
| `result`          | X  | 소비되지 않은 `RESULT_READY`일 때만 값이 있다. RecipeDraft(JSON)                                 |
| `failureCode`     | X  | `FAILED`일 때만. `SOURCE_UNAVAILABLE`, `CONTENT_NOT_RECOGNIZED`, `PROCESSING_FAILED`  |
| `previewImageUrl` | X  | 3단계, Instagram만. embed에서 얻은 이미지 URL                                                |
| `attempt`         | O  | 선점 횟수. 늦게 끝난 처리의 결과를 버리는 기준이자 stale 복구를 1회로 제한하는 기준                                 |
| `createdAt`       | O  | 일일 한도 계산 기준                                                                         |
| `startedAt`       | X  | 마지막 선점 시각. 120초 deadline, stale 판정, 실패 Job 7일 정리의 기준                               |
| `expiresAt`       | X  | `RESULT_READY`가 된 시각 + 24시간                                                         |
| `consumedAt`      | X  | Recipe로 저장된 시각. 이후 Job은 영구 보존된다                                                     |

- **입력 조합은 DB CHECK로 강제한다.** `IMAGE`면 URL이 없고 Key가 1개 이상, 나머지는 URL이 있고 Key가 없다. 한 행 안에서 판단이 끝나서 가능하다.
- enum 값 3종(`sourceType`, `status`, `failureCode`)도 CHECK로 고정한다.
- 상태와 `result`·`failureCode`·시각의 조합은 CHECK로 묶지 않고 도메인 로직이 지킨다.
- 인덱스는 두 개다. `QUEUED` 부분 인덱스는 선점용이고, (`userId`, `createdAt`)은 일일 한도용이다.
- `expiresAt`은 계산하지 않고 저장한다. TTL 설정을 바꿨을 때 이미 만든 Job의 만료 시각까지 바뀌지 않게 하기 위해서다.

#### RecipeDraft (`result`)

| 속성                | 필수 | 규칙                                                     |
|-------------------|----|--------------------------------------------------------|
| `title`           | X  | 255자 이하                                                |
| `categoryCode`    | X  | Recipe 카테고리 7종                                         |
| `cookTimeMinutes` | X  | 1~1440 정수                                              |
| `servings`        | X  | 1 이상 정수                                                |
| `ingredients[]`   | O  | 배열. 원소는 `ingredientId`(X), `name`(O, 255자), `amountText`(X, 255자) |
| `steps[]`         | O  | 배열. 원소는 `content`(O)                                   |

값이 있는 필드는 `POST /recipes` 검증 범위 안에 있다. `title`·`categoryCode`가 `null`이면 사용자가 폼에서 채운다.

#### Recipe에 추가하는 속성 (2단계)

| 속성                | 필수 | 규칙                                  |
|-------------------|----|-------------------------------------|
| `ingestionJobId`  | X  | 원본 Job. UNIQUE·FK. `MANUAL`이면 없다   |
| `sourceUrl`       | X  | URL 방식의 원본 URL 스냅샷                  |
| `sourceImageKeys` | X  | 사진 방식의 원본 사진 Key 목록 스냅샷             |

- `registrationMethod`와 세 속성의 조합은 CHECK로 강제한다.
  - `MANUAL`: 셋 다 없음
  - `URL`: Job과 URL
  - `IMAGE`: Job과 Key 1개 이상
- 출처와 등록 방식은 생성 후 수정할 수 없다.

### 3.3. API 설계

| Method | Endpoint                                  | 기능       |
|--------|-------------------------------------------|----------|
| `POST` | `/api/v1/ingestion-jobs`                  | 분석 요청    |
| `GET`  | `/api/v1/ingestion-jobs/{ingestionJobId}` | 분석 상태 조회 |

모든 API는 인증된 사용자만 호출할 수 있다. Job이 없거나 다른 사용자의 것이면 `404 + INGESTION_JOB_NOT_FOUND`로 처리한다.

#### 분석 요청

| 속성               | 필수 | 규칙                                                          |
|------------------|----|-------------------------------------------------------------|
| `inputType`      | O  | `URL` 또는 `IMAGE`                                            |
| `url`            | X  | `URL`일 때 필수이고 `IMAGE`면 보내면 안 된다. 최대 2048자                   |
| `inputImageKeys` | X  | `IMAGE`일 때 필수이고 `URL`이면 보내면 안 된다. 1개 이상 최대 개수(초안 10) 이하, 빈 값·중복 불가 |

```json
{ "inputType": "IMAGE", "inputImageKeys": ["ingestion-inputs/7/3f1c….jpg", "ingestion-inputs/7/9ab2….png"] }
```

```json
{ "status": 202, "message": "레시피 분석 요청이 접수되었습니다.", "data": { "ingestionJobId": 321 } }
```

- **받는 URL은 단계마다 늘어난다.** 1단계는 모든 URL을 거절하고, 2단계에 YouTube, 3단계에 Instagram을 받는다. 허용 형식과 정규화 규칙은 해당 단계 계획서가 정한다.
- **일일 한도는 오늘(Asia/Seoul 자정 기준) 만든 Job 수로 센다.**
  - 실패한 Job도 센다. 분석 비용이 이미 나갔기 때문이다.
  - 요청 검증에서 거절돼 Job이 생기지 않은 요청은 세지 않는다.
  - 동시 요청이면 1~2건 넘을 수 있다. 비용 방어용이라 허용한다.
  - 429에 `Retry-After` 헤더는 두지 않는다(02-4 계약). 한도는 Asia/Seoul 자정에 초기화되고, 안내는 앱이 한다.
  - 이것은 Ingestion의 분석 비용을 막는 장치이고, 서비스 전체의 Rate Limit 정책이 아니다(02-0 §4는 보류 상태).
  - 도입 여부와 값(초안 20)은 1단계 머지 전 팀 합의가 필요하다(§5).
- **다시 시도는 새 Job이다.**
  - 사진 입력이면 앱이 사진을 다시 올려 새 Key로 보낸다. Key는 한 번만 연결되므로 같은 Key는 `409`다.
  - 202 응답을 못 받고 다시 보내도 `409`이고, 먼저 만들어진 Job은 그대로 남는다.
- **사진 크기는 앱이 줄여서 올린다**(예: 긴 변 2048px). 서버는 사진을 읽기 전에 크기부터 확인하고, 합계가 14MB를 넘으면 분석을 실패로 끝낸다(§4.4).
- **대표 사진은 따로 올린다.** 이미지 등록 화면에서 고른 대표 사진은 `RECIPE_COVER` 용도로 업로드해 Recipe 저장 때 `coverImageKey`로 보낸다. 분석 입력 사진은 한 곳에만 연결되므로 대표 사진으로 다시 쓸 수 없다.

| 상황                            | 응답                                         |
|-------------------------------|--------------------------------------------|
| 입력 조합 위반, 개수 초과, 중복, 빈 Key    | `400 + REQUEST_VALIDATION_FAILED`          |
| 정의되지 않은 `inputType`           | `400 + INVALID_REQUEST_FORMAT`             |
| 지원하지 않는 URL                   | `400 + INGESTION_URL_UNSUPPORTED`          |
| Key가 없음, 남의 것, 다른 용도, 업로드 안 됨 | `400 + INGESTION_INPUT_IMAGE_INVALID`      |
| Key가 이미 다른 곳에 연결됨             | `409 + INGESTION_INPUT_IMAGE_ALREADY_USED` |
| 일일 한도 초과                      | `429 + INGESTION_DAILY_LIMIT_EXCEEDED`     |

#### 분석 상태 조회

```json
{
  "status": 200,
  "message": "레시피 분석 작업을 조회했습니다.",
  "data": {
    "ingestionJobId": 321,
    "inputType": "IMAGE",
    "status": "RESULT_READY",
    "previewImageUrl": "https://storage.googleapis.com/…",
    "result": {
      "title": "스팸감자전",
      "categoryCode": "KOREAN",
      "cookTimeMinutes": null,
      "servings": null,
      "ingredients": [ { "ingredientId": 31, "name": "감자", "amountText": "2개" } ],
      "steps": [ { "content": "감자를 곱게 간다." } ]
    },
    "failureCode": null
  }
}
```

- **`status`**: `expiresAt`이 지난 미소비 `RESULT_READY`는 정리 작업이 돌기 전이어도 `EXPIRED`로 보인다.
- **`result`**: 보이는 상태가 `RESULT_READY`이고 소비되지 않았을 때만 객체다.
- **`failureCode`**: `FAILED`일 때만 값이 있다.
- **`ingredientId`**: 재료명을 소문자로 바꾸고 공백을 모두 뺀 뒤, 활성 재료의 이름·별칭 중 **정확히 하나**와 같을 때만 값이 있다. 없거나 여러 개면 `null`이다.
- **`previewImageUrl`**: 분석 중 화면에 원본 썸네일을 보여 주는 용도다.
  - 사진: 첫 사진의 조회 URL. 조회할 때마다 트랜잭션 밖에서 만들고, 실패하면 `null`
  - YouTube: `https://i.ytimg.com/vi/{id}/hqdefault.jpg`. URL에서 계산한다
  - Instagram(3단계): embed에서 얻은 이미지 URL. Worker가 읽기 전인 `QUEUED` 동안은 `null`
  - 소비된 Job: `null`. Recipe가 삭제되면 원본 사진도 지워지기 때문이다
- 원본 URL, 사진 Key, 시각, 시도 횟수는 노출하지 않는다.
- 7일 정리로 삭제된 Job은 `404`다.

#### Recipe 생성 연동 (2단계)

`POST /api/v1/recipes`에 `ingestionJobId`를 함께 보낸다. 요청 모델과 응답은 [Recipe Spec](./recipe.md)을 따르고, Ingestion 기준의 결과는 다음과 같다.

| 조건                                | 결과                                     |
|-----------------------------------|----------------------------------------|
| Job이 없거나 다른 사용자가 소유함              | `404 + INGESTION_JOB_NOT_FOUND`        |
| 이 Job으로 만든 Recipe가 이미 있음          | 기존 Recipe ID와 `200 OK`                 |
| 소비됐는데 Recipe가 없음(삭제됨)             | `409 + INGESTION_JOB_ALREADY_CONSUMED` |
| 보이는 상태가 `EXPIRED`                 | `409 + INGESTION_JOB_EXPIRED`          |
| 보이는 상태가 `RESULT_READY`가 아님        | `409 + INGESTION_JOB_INVALID_STATE`    |
| 위에 해당하지 않음                        | 최초 생성 후 `201 Created`                  |

- **출처와 등록 방식은 요청으로 받지 않고 Job에서 가져온다.**
  - 사진 Job: `IMAGE`, 사진 Key 목록 복사
  - YouTube·Instagram Job: `URL`, 원본 URL 복사
- 레시피 내용은 사용자가 보낸 값 그대로 저장하고, 재료 `ingredientId`는 기존 재료 검증을 거친다.
- 상세 조회의 `source`는 [Recipe Spec](./recipe.md) 계약대로 `{sourceType, originalUrl}`이고, 사진으로 만든 Recipe는 `originalUrl`이 `null`이다.

### 3.4. 트랜잭션과 동시성 제어

#### 분석 요청

검증, 일일 한도, 사진 연결, Job 저장을 하나의 DB 트랜잭션에서 처리한다.

사진은 기존 Upload 연결을 Key마다 차례로 호출하고, 처음 실패한 Key에서 예외를 던진다. 앞서 연결한 Key도 함께 롤백된다.

연결 전 파일 존재 확인은 원격 호출이라(최대 5초) 트랜잭션 안에서 Key 수만큼 일어난다. GCS 장애 때 사진 10장이면 최악 50초 동안 DB 커넥션을 쥔다. 평소에는 수백 ms이고, 여러 Key를 한 번에 연결하는 새 기능과 "실패하면 호출자가 반드시 롤백한다"는 규칙을 만들지 않기 위해 감수한다.

#### 선점

- **짧은 트랜잭션에서 잠그고 표시한다.** `QUEUED` Job을 `id` 순으로 빈 슬롯 수만큼 `FOR UPDATE SKIP LOCKED`로 잠근다. 그 트랜잭션에서 `PROCESSING`, `attempt + 1`, `startedAt`을 기록하고 바로 커밋한다.
- **같은 Job을 둘이 가져가지 않는다.** 두 선점이 동시에 돌아도 한쪽이 잠근 행은 다른 쪽이 건너뛴다. 커밋 뒤에 다시 가져가는 것은 잠금이 아니라 `PROCESSING` 상태값이 막는다.
- **외부 호출은 트랜잭션 밖에서 한다.** GCS 읽기와 Gemini 호출이 해당한다. DB 커넥션은 선점과 결과 저장 순간에만 쓴다.

#### 결과 저장

- **조건이 맞을 때만 저장한다.** `PROCESSING`이고 `attempt`가 선점 때와 같을 때만 `RESULT_READY` 또는 `FAILED`로 바꾼다. 조건이 맞지 않으면 stale 복구로 다시 넣어졌거나 다른 시도가 가져간 경우이므로, 늦게 끝난 결과는 버린다.
- **처리를 시작하기 전에도 같은 조건을 확인한다.** 이미 무효가 된 시도가 Gemini를 중복 호출하지 않게 하기 위해서다.

#### 시간과 재시도

- **Job 전체 deadline은 `startedAt`부터 120초다.** 남은 시간이 너무 짧으면 새 호출을 시작하지 않고 `PROCESSING_FAILED`로 끝낸다. 그래서 Job 하나가 stale 기준 3분과 겹치지 않는다.
- **단계마다 상한을 두고, 남은 시간을 넘지 않게 한다.** 호출 timeout은 `min(단계 상한, 남은 시간)`이다. 단계는 Gemini 분석, 그리고 3단계의 원본 다운로드·파일 업로드·처리 완료 대기다. 단계를 나누는 이유는 실측에서 파일 업로드만 6회 중 1회가 366초 걸렸기 때문이다.
- **재시도는 처리 시도 한 번 안에서 2회까지다.** 외부 호출 재시도와 stale 복구는 따로 센다. 즉 stale로 다시 선점되면 그 시도에서 예산이 새로 시작된다.
- **대기 시간은 2초 → 8초로 늘리고 jitter를 더한다.** Gemini가 `RetryInfo`로 대기 시간을 주면 그 값을 우선한다. 대기 뒤 남은 시간이 부족하면 재시도하지 않는다.
- **설정값으로 관리하는 것**: Worker 켜기, 동시 처리 수, 확인 주기(2초), deadline(120초), stale 기준(3분), 대기 상한(10분), 결과 유효기간(24시간), 보존 기간(7일), 재시도 횟수·대기, 단계별 상한, 사진 합계 상한(14MB), 사진 최대 개수, 일일 한도, 모델 이름. 값을 코드에 고정하지 않는다.

| 결과                       | 조건                                                                          |
|--------------------------|-----------------------------------------------------------------------------|
| 재시도                      | timeout, 연결 끊김, 5xx, `RetryInfo`가 있는 429                                    |
| `CONTENT_NOT_RECOGNIZED` | 안전 차단 응답, 레시피가 아니거나 여러 개라는 판정, 정규화 후 재료·단계가 모두 없음                            |
| `SOURCE_UNAVAILABLE`     | YouTube 입력의 400(없거나 비공개인 영상), Instagram 수집 실패(3단계)                          |
| `PROCESSING_FAILED`      | 그 밖의 전부. 재시도 소진, `RetryInfo`가 없는 429(선불 잔액 소진 등), 인증·모델 설정 오류, 응답 해석 실패, 사진 합계 14MB 초과 |

- **오류를 분류하는 곳과 결정하는 곳이 다르다.** 외부 Adapter는 실패를 재시도 가능·불가능과 원인 종류로 분류하기만 한다. 재시도 여부와 최종 `failureCode`는 Worker가 정한다(04-3 규칙).

**로그**

- 재시도하는 실패는 WARN으로 남기고 같은 Stack Trace를 반복해 남기지 않는다. 재시도를 모두 쓴 최종 실패와 예상하지 못한 오류는 ERROR로 남긴다. 기록은 Worker가 한 번만 한다.
- 남기는 식별자는 `ingestionJobId`, `sourceType`, `attempt`, 소요 시간, 토큰 사용량이다.
- API 키, 서명 URL, CDN URL, caption, Gemini 응답 원문은 로그와 예외 메시지에 남기지 않는다.

#### 주기 작업

| 주기  | 작업                                                                                    |
|-----|---------------------------------------------------------------------------------------|
| 1분  | `startedAt`에서 3분이 지난 `PROCESSING`: `attempt`가 1이면 `QUEUED`로 되돌리고, 2면 `FAILED`(`PROCESSING_FAILED`) |
| 1분  | `createdAt`에서 10분이 지난 `QUEUED`: `FAILED`(`PROCESSING_FAILED`). Worker가 꺼졌거나 멈췄을 때 앱이 끝없이 조회하지 않게 한다 |
| 1시간 | `expiresAt`이 지난 미소비 `RESULT_READY`: `EXPIRED`로 바꾸고 `result` 삭제                        |
| 1시간 | 미소비 Job 삭제 + 입력 사진 해제. `FAILED`는 `startedAt`, `EXPIRED`는 `expiresAt`에서 7일 뒤          |

- **여러 대에서 동시에 돌아도 안전하다.** 상태 전이는 모두 조건부 UPDATE 한 문장이고, 7일 정리는 조건부 DELETE로 지운 행의 Key만 이어서 해제한다.
- **만료와 Recipe 저장이 경쟁해도 안전하다.** Recipe 저장이 먼저 Job을 잠그면, 만료 UPDATE는 기다렸다가 `consumedAt` 조건을 다시 평가해 그 행을 건너뛴다.
- **만료가 1시간 주기여도 계약은 달라지지 않는다.** 조회와 Recipe 저장이 `expiresAt` 시각으로 판단하기 때문이다. `result`가 최대 1시간 더 남을 뿐이다.
- **7일 정리는 잠그고 재확인하지 않는다.** 대상이 끝난 상태의 미소비 Job이라 경쟁 상대가 없다. 조건부 DELETE로 지우면서 사진 Key를 얻고, 같은 트랜잭션에서 Upload에 해제를 요청한 뒤, 파일은 커밋 후 GCS에서 지운다. 소비된 Job은 지우지 않으며, Recipe의 FK도 삭제를 막는다.

#### 재배포

처리 중이던 Job은 stale 복구가 3분 뒤 한 번 다시 처리한다. 최대 약 3분 늦어지는 것을 감수한다. 다시 끊기면 `PROCESSING_FAILED`로 끝난다.

#### Recipe 생성 연동 (2단계)

한 트랜잭션에서 다음 순서로 처리한다.

1. Job을 비관적 쓰기 잠금(`SELECT ... FOR UPDATE`)하고 소유권을 확인한다.
2. 이 Job으로 만든 Recipe가 있으면 기존 Recipe ID와 `200 OK`를 반환하고 끝낸다.
3. 보이는 상태를 확인해 `409` 세 가지 중 하나로 거절하거나, `consumedAt`을 기록하고 `result`를 비운다.
4. 출처를 복사해 Recipe를 저장한다.

- **2번이 3번보다 먼저인 이유**: 응답을 못 받고 다시 누른 정상 요청이 "이미 소비됨"으로 거절되지 않게 하기 위해서다.
- **같은 요청이 동시에 와도 Recipe는 하나다.** Job 행 잠금이 순서를 세우고, `recipe.ingestion_job_id` UNIQUE가 마지막 방어선이다.
- **입력 사진은 다시 연결하지 않는다.** Job을 만들 때 이미 연결됐고, UploadObject의 용도는 Recipe로 넘어간 뒤에도 `INGESTION_INPUT`이다.
- 어느 단계에서든 실패하면 전체를 롤백한다.

#### Recipe 삭제 (2단계)

대표 이미지를 해제하는 자리에서 원본 사진 Key도 `INGESTION_INPUT` 용도로 함께 해제하고, 파일은 커밋 후 GCS에서 지운다.

Job 행과 `consumedAt`은 남긴다. 같은 Job으로 다시 저장하면 `409 + INGESTION_JOB_ALREADY_CONSUMED`다.

### 3.5. 외부 연동

#### Gemini

- **호출**: Gemini Developer API의 `generateContent`를 REST로 직접 부르고, 선불로 결제한다. 기본 모델은 `gemini-3.5-flash-lite`(설정값)다.
  - 실측에서 이미지 1.8~2.7초, Shorts 3.4초로 후보 중 가장 빠르고 저렴했다.
  - 대신 조리시간 값이 실행마다 흔들렸다.
- **출력 모양**은 `responseJsonSchema`로 고정한다. `verdict`(`RECIPE`, `NOT_RECIPE`, `MULTIPLE_RECIPES`)에 RecipeDraft 필드를 더한 모양이다. 재료 `ingredientId`는 서버가 매칭하므로 스키마에서 뺀다.
- **추출 규칙**
  - 원본에서 확인되는 정보만 쓴다. 일반 레시피 지식으로 채우지 않는다. 카테고리 분류만 예외다.
  - 한국어로 쓴다. 외국어 콘텐츠면 번역한다.
  - 재료 `name`은 짧은 재료명, `amountText`는 원문 표기를 유지한다.
  - 조리 단계는 실제 순서대로 쓰고, 광고·인트로·구독 요청은 뺀다.
  - 조리시간·인분은 원본에 명시된 경우만 넣는다. 범위로 적혀 있으면 조리시간은 큰 값("30~40분" → 40), 인분은 작은 값("2~3인분" → 2)을 쓴다.
  - 서로 다른 레시피가 여러 개면 하나를 고르지 않고 `MULTIPLE_RECIPES`로 답한다.
- **외부 텍스트**(caption)는 "분석할 데이터" 블록으로만 넣고, 그 안의 지시는 따르지 않게 한다.
- **사진 입력**은 앞에 `입력: 이미지 {n}장. 순서대로 하나의 레시피를 이룰 수 있다.` 한 줄을 붙인다.
- **서버 정규화**
  - `verdict`가 `NOT_RECIPE`·`MULTIPLE_RECIPES`면 `CONTENT_NOT_RECOGNIZED`로 끝낸다.
  - 앞뒤 공백을 지우고 빈 재료·단계를 뺀다. `title`·재료 `name`·`amountText`가 255자를 넘으면 자른다. 조리 단계는 자르지 않는다(Recipe API에 길이 제한이 없다).
  - 범위를 벗어난 숫자는 `null`로 바꾼다.
  - 그 뒤 재료와 단계가 모두 없으면 `CONTENT_NOT_RECOGNIZED`다.

#### Instagram (3단계)

- **게시물**: 이미지를 받아 요청에 넣는다.
  - `img_index`가 있으면 그 카드만 분석한다.
  - 없으면 이미지 카드 전부를 caption과 함께 한 번에 분석하고 `verdict`로 판정한다.
  - 영상 카드는 분석하지 않는다.
- **Reel**: 영상을 임시 파일로 받아 Gemini Files API로 올린 뒤, 처리 완료(ACTIVE)를 확인하고 분석한다. 업로드한 파일과 임시 파일은 성공·실패와 무관하게 지운다.
  - 단계마다 상한을 두고, 넘으면 한 번 재시도한 뒤 실패로 끝낸다. 실측에서 업로드가 한 번 366초 걸렸는데, 이런 경우까지 기다리려고 120초 deadline을 늘리지 않는다. 사용자는 다시 시도하면 된다.
  - 3단계에서 Reel 처리 시간의 p95·p99를 측정해 deadline과 단계별 상한을 다시 조정한다.
- **요청 대상 제한**
  - embed 주소는 파싱한 게시물 코드로 서버가 직접 만든다.
  - 미디어는 https이면서 Instagram CDN 허용 호스트(`cdninstagram.com`, `fbcdn.net` 계열)일 때만 받는다.
  - 영상은 스트리밍으로 받으면서 크기 상한과 MIME을 검증한다.
  - CDN URL과 caption 원문은 로그에 남기지 않는다.

## 4. 주요 설계 결정과 선택 이유

### 4.1. Worker를 API와 같은 프로세스에서 실행

| 대안                               | 장점                    | 단점                                                         |
|----------------------------------|-----------------------|------------------------------------------------------------|
| 처음부터 API·Worker 별도 컨테이너           | 장애·자원이 격리됨            | VM 한 대에 JVM 2개. 배포·로그·헬스체크가 두 벌. 트래픽이 없는 MVP에서는 격리 이득이 작음 |
| Worker만 Cloud Run                | Worker 자원 분리와 자동 확장   | 실행 환경이 두 벌이고 VPC 연결이 필요. DB가 VM에 있어 VM 장애 시 함께 멈춤          |
| Cloud Tasks 관리형 큐               | 재시도·동시성 제한을 GCP가 맡음   | DB 저장과 큐 등록을 맞추는 장치(outbox), 콜백 인증, 로컬 에뮬레이션이 필요            |
| **같은 프로세스 + Gemini 키를 가진 쪽이 맡음 (채택)** | 배포 단위 하나, 인프라 추가 없음   | 분석이 API와 메모리·커넥션 풀을 나눠 씀. 동시 처리 수로 제한                      |

- **Gemini 키를 가진 프로세스가 Worker를 맡는다.** 켜고 끄는 설정을 따로 두지 않는다. 스위치를 두면 운영 배포에서 켜는 것을 잊었을 때 앱이 정상으로 보이면서 분석만 멈추고, 기동도 헬스체크도 통과해 오류로 드러나지 않는다. 키가 없으면 어차피 분석할 수 없으므로 키의 유무가 곧 답이다.
  - Worker를 맡은 프로세스는 시작할 때 로그 한 줄을 남긴다.
  - 팀원 로컬은 키 없이 앱이 그대로 뜨고 Worker만 맡지 않는다. 만든 Job은 `QUEUED`로 남는다 — 대기 상한 정리도 Worker의 주기 작업이라 함께 돌지 않는다.
- **분리는 코드 변경 없이 할 수 있다.** 필요해지면 같은 이미지를 하나 더 띄우고, API 쪽 프로세스에는 키를 주지 않는다.
- **이 결정의 범위는 Ingestion Worker다.** 알림(Notification) 스케줄러·발송을 어디서 돌릴지는 Notification 설계에서 정한다. 기존 BE 문서는 둘을 하나의 Worker 프로세스로 묶어 두었다.
- **Service Account는 계속 하나다.** 같은 프로세스라 API도 Gemini 키를 갖게 되므로, "Worker가 API에 불필요한 권한을 가지면 SA를 분리한다"는 후속 조건은 이 구조에 맞게 다시 적어야 한다(§6).

### 4.2. PostgreSQL Job 테이블을 2초마다 확인

| 대안                                | 장점                   | 단점                                                   |
|-----------------------------------|----------------------|------------------------------------------------------|
| 요청 스레드에서 바로 비동기 실행                | 지연이 없고 단순함           | 재배포·장애 때 작업이 사라지고, 요청이 몰리면 그만큼 Gemini를 동시에 호출함        |
| 커밋 직후 Worker 깨우기 + 느린 주기 확인        | 처리 시작 지연이 거의 없음      | 실행 슬롯 관리 코드가 늘고, 오류 경로에서 슬롯이 새면 Worker가 멈춤              |
| 메시지 브로커                           | push 전달, 여러 소비자에 전달  | 인프라 추가, DB 저장과 발행을 맞추는 outbox                        |
| **2초 주기 확인 + `SKIP LOCKED` (채택)** | 가장 단순. 작업이 DB에 남아 유실 없음 | 처리 시작이 최대 2초 늦음                                     |

분석 자체가 3~30초라 2초 지연은 체감이 작다. 우리 규모에서는 DB 큐보다 Gemini 한도와 비용이 먼저 한계에 닿는다.

### 4.3. Gemini를 SDK 없이 REST로 직접 호출

| 대안                        | 장점                            | 단점                                                                  |
|---------------------------|-------------------------------|---------------------------------------------------------------------|
| google-genai Java SDK     | 요청·응답 타입을 제공                  | 기본으로 자동 재시도 5회에 timeout 무제한. 예외 메시지에 응답 원문이 붙음. Jackson 2·OkHttp가 추가됨 |
| Spring AI 2.0             | 호출 코드가 가장 짧음                  | 내부가 SDK라 같은 문제를 물려받음. Files API와 안전 차단 정보가 없고, 예외가 한 종류로 뭉개짐     |
| **RestClient 직접 호출 (채택)** | 재시도·timeout·오류 분류를 직접 쥠. 의존성 추가 없음 | 요청·응답 DTO를 직접 쓰고, API 변경을 직접 따라가야 함                                 |

- **REST를 고른 이유:** 쓰는 기능이 `generateContent`와 Files API로 좁다. Adapter가 할 일은 외부 실패를 재시도 가능·불가능으로 분류하는 것인데, 이 분류는 HTTP 응답과 오류 본문을 직접 볼 때 가장 확실하다. 그 분류를 받아 재시도와 `failureCode`를 정하는 것은 Worker다(04-3).
- **Developer API를 고른 이유:** Vertex AI에는 Files API가 없어 Reel을 GCS에 임시 저장해야 하고, YouTube·외부 입력 경로도 실측되지 않았다.
- **Developer API의 대가:** 선불 결제만 된다. 잔액이 0이면 모든 호출이 멈추므로 자동 충전과 잔액 알림이 필요하다.

### 4.4. 사진은 서버가 읽어 요청에 직접 넣음

| 대안                          | 장점                  | 단점                                                            |
|-----------------------------|---------------------|---------------------------------------------------------------|
| Gemini에 GCS 서명 URL 전달       | 서버가 사진을 받지 않음       | 403으로 막힘                                                      |
| Files API로 올린 뒤 참조           | 요청 크기 한도가 없음        | 업로드와 처리 대기 호출이 추가됨. 업로드 6회 중 1회가 366초 걸린 꼬리 지연 실측              |
| **서버가 GCS에서 읽어 inline (채택)** | 호출 1회, 이미지 분석 약 2~3초 | 합계 14MB 상한. 앱이 사진을 줄여 올려야 함                   |

- **외부 URL 입력이 막힌 것은 실측 결과다(2026-09-12).** 확인한 조합은 다음과 같다.
  - URL: GCS 서명 URL, Instagram CDN URL, 서명 없는 공개 URL
  - 호출 경로: `generateContent`, Interactions API
  - 키: 무료 키, 선불 키
- 이 조합이 모두 403이었다. 이 결정을 뒤집으려면 같은 방식으로 반증해야 한다.
- 업로드에는 크기 제한이 없어([Upload Spec](./upload.md) §2.2), 서버가 사진 바이트를 처음 받는 곳이 여기다. 그래서 바이트를 읽기 전에 크기부터 확인한다.
- **14MB는 Gemini의 요청 한도가 아니라 우리가 정한 상한이다.** 실측(2026-09-12)에서 100MiB(요청 전체 133MB)까지 보내도 거절 없이 정상 처리됐고, 30~100MiB 전 구간에서 입력 토큰 수가 1,112개로 똑같았다. Gemini가 큰 이미지를 내부적으로 축소해서 분석하므로, 원본을 더 크게 보내도 분석 품질이 나아지지 않는다. 상한을 두는 이유는 같은 JVM(API+Worker)의 메모리를 지키고, 사진을 읽고 base64로 바꾸는 시간이 120초 deadline을 갉아먹지 않게 하기 위해서다. 값은 폰 사진 압축 크기(장당 몇 MB)를 여러 장 감당할 수 있게 잡은 것이다.

### 4.5. 입력 사진 Key를 배열 컬럼으로 저장

| 대안                                         | 장점                    | 단점                                                   |
|--------------------------------------------|-----------------------|------------------------------------------------------|
| `IngestionJobImage`·`RecipeSourceImage` 1:N 테이블 | Key별로 FK·UNIQUE를 걸 수 있음 | Entity·컬렉션 매핑·삭제 순서가 늘고, 입력 규칙을 앱 로직으로만 지킬 수 있음   |
| **`text[]` 배열 (채택)**                       | 입력 조합을 한 행 CHECK로 강제하고 테이블·조인이 없음 | Key별 DB 제약이 없음                                      |

Key 목록은 Job에서도 Recipe에서도 통째로 쓰고 통째로 읽는다. 같은 Key가 두 곳에 연결되는 것은 UploadObject가 이미 막는다. 배열 컬럼은 `ingredient.aliases` 선례가 있다.

### 4.6. 출처를 Recipe 컬럼으로 두고 소비된 Job은 영구 보존

| 대안                                                   | 장점                              | 단점                                                     |
|------------------------------------------------------|---------------------------------|--------------------------------------------------------|
| `RecipeSource`·`RecipeSourceImage` 테이블                | 출처 정보가 늘어도 Recipe가 커지지 않음        | Entity·조인·삭제 순서가 늘고, JobImage를 옮기고 지우는 단계가 필요          |
| Recipe가 `ingestionJobId`만 참조                          | 컬럼 하나                            | 상세 조회와 삭제가 매번 Ingestion을 호출해야 함                        |
| **`ingestionJobId`·`sourceUrl`·`sourceImageKeys` 컬럼 (채택)** | 상세 조회는 기존 쿼리 그대로, 삭제는 Key를 읽어 해제만 함. UNIQUE 하나로 중복 저장을 막음 | `MANUAL` Recipe에 빈 컬럼 3개. 채널명 같은 출처 정보가 늘면 테이블로 분리     |

- **컬럼으로 충분한 이유:** 출처는 Recipe와 1:0..1이고 따로 조회하거나 따로 사는 데이터가 아니다.
- **사진을 옮기는 단계가 없다.** 입력 사진은 소비되지 않은 Job만 소유하고, 소비된 Job은 중복 저장을 막는 기록이라 영구 보존한다. 그래서 사진을 옮기거나 지우는 단계가 필요 없다.
- **플랫폼 값(`source_platform`)은 저장하지 않는다.** Job의 `sourceType`이 이미 갖고 있고, Recipe가 밖에 보여 주는 것은 `URL`·`IMAGE` 구분과 원본 URL뿐이다.
- **바뀌는 기존 결정:** [Recipe Spec](./recipe.md) §4.3은 "Recipe에 원본 필드를 직접 추가"하는 안을 "MANUAL Recipe에 불필요한 nullable 필드가 늘고 Recipe가 비대해진다"는 이유로 기각했다. 출처가 세 컬럼에 그치는 지금 범위에서는 그 비용이 테이블을 늘리는 비용보다 작다고 보고 이 안을 채택한다. 같은 §4.3이 "Recipe가 IngestionJob만 참조"하는 안을 기각한 이유(Job의 임시 데이터 생명주기에 의존)는 그대로 유효하며, 이 설계도 그 안은 쓰지 않는다.

### 4.7. 분석 결과를 등록 폼과 같은 모양으로

| 대안                                          | 장점                    | 단점                                            |
|---------------------------------------------|-----------------------|-----------------------------------------------|
| 원문 문자열(`cookTimeText`·`servingsText`)       | 원본 표현을 보존             | 앱이 "약 30~40분" 같은 표현을 해석해야 함                   |
| **폼·`POST /recipes`와 같은 모양 + 서버 재료 매칭 (채택)** | 앱이 폼에 그대로 채우고 그대로 저장  | 범위 표현을 정수로 바꾸는 규칙이 필요                         |

- **폼과 같은 모양인 이유:** 디자인상 결과 확인은 "레시피 직접 입력" 폼에 값을 채우는 흐름이다.
- **재료 매칭은 서버가 한다.** 검토한 대안은 둘이었다.
  - Gemini에 재료 목록을 주고 고르게 하기: 토큰이 늘고 없는 id를 지어낼 수 있다.
  - 매칭하지 않기: 분석으로 만든 레시피가 보유 재료 기반 추천에서 빠진다.
- **정확히 하나가 맞을 때만 채운다.** 별칭 "돼지고기"는 시드에서 재료 4개에 붙어 있어, 서버가 하나를 고르면 틀린 연결이 생긴다.

### 4.8. 비용 방어로 사용자별 일일 한도

| 대안                    | 장점                   | 단점                                              |
|-----------------------|----------------------|-------------------------------------------------|
| 제한 없이 예산 알림만          | 가장 단순                | 알림 전까지 비용이 계속 나가고, 잔액이 떨어지면 모든 사용자의 분석이 멈춤        |
| 전체 차단 스위치만            | 코드가 적음               | 사람이 직접 꺼야 하고, 한 사람이 모두의 몫을 씀                   |
| **사용자별 일일 한도 (채택)** | 조회 한 번으로 한 사용자의 폭주를 막음 | 앱이 알아야 하는 새 공개 계약(429)이라 팀 합의가 필요              |

## 5. 미결정 사항

| #   | 질문                                                     | 결정 주체  | 막는 것    |
|-----|--------------------------------------------------------|--------|---------|
| OQ1 | 일일 한도 429 도입과 값(초안 20)                                 | 팀      | 1단계 머지 |
| OQ2 | 사진 최대 개수(초안 10)                                        | 제품     | 없음      |
| OQ3 | 앱의 업로드 전 사진 축소, 다시 시도할 때 사진 재업로드                        | FE     | 1단계 머지 |
| OQ4 | 같은 URL 결과 재사용, URL로 등록한 레시피의 대표 사진                      | 제품     | 없음      |
| OQ5 | carousel 영상 카드 지원                                      | 제품     | 없음      |
| OQ6 | Instagram 비공식 수집 수용                                    | 팀      | 3단계     |
| OQ7 | GCE IP에서 Instagram embed·CDN 접근                         | 실측     | 3단계     |
| OQ8 | 폼의 조리시간 칸(상세에는 보이는데 입력·편집에 없음), 사진 등록 흐름의 분석 중 화면 문구("영상 속") | 디자인    | 없음      |
| OQ9 | 작은 Reel을 Files API 없이 inline으로 보내기, 해상도 낮추기(속도 최적화)      | 실측     | 없음      |
| OQ10 | `img_index`가 없는 carousel을 "이미지 카드 전부 분석"으로 둘지(§3.5). BE 문서에서는 아직 미결정 항목이다 | 팀·제품 | 3단계     |
| OQ11 | 선불 잔액 자동 충전·알림 기준. 00-1의 Observability·알림 항목과 함께 정한다        | 팀·운영  | 없음      |

## 6. 이 스펙이 바꾸는 기존 문서

해당 단계를 머지할 때 함께 고친다.

로컬 BE 문서와 Notion 페이지는 같은 작업에서 함께 고친다.

- **`00 BE 결정사항`** — 날짜 항목을 그대로 두고 새 항목에서 대체 목록을 밝힌다.
  - 08-26 `RecipeSource.original_url`, 08-28 `input_type`·`INGESTION_JOB_IMAGE`·`source_platform`·`error_code`
  - 09-02 Source 처리 전략(사진 inline), timeout·재시도(429 조건, 재시도 경계), TTL·보존, 실행 구조(API·Worker 분리), Maintenance
  - 09-05 `RecipeSourceImage`, 09-07 배포 구조의 컨테이너 3개와 SA 분리 후속 조건
- **`00-1 미결정·정합성 확인사항`** — "Ingestion 실행·보존 구현" 확정 문구, 해결 항목의 JobImage 이관 설명, 캐러셀 미결정 항목
- **`01-0` 도메인 맵, `02-5 Ingredient API`** — Ingredient 사용처에 Ingestion 추가
- **`01-1 Recipe`** — ERD와 §4 본문. `RecipeSource`·`RecipeSourceImage`·`IngestionJobImage`, 컬럼 이름(`processing_started_at`·`processing_attempt_count`·`error_code`·`input_type` → `startedAt`·`attempt`·`failureCode`·`sourceType`), `cookTimeText`·`servingsText`
- **`02-0 API 공통`** — §2의 사진 소유 도메인 이전 문장, §4 보류의 Rate Limiting과의 관계
- **`02-1 Recipe API`** — `RecipeSource` UNIQUE와 JobImage 삭제 문장. 상세 `source` 계약 자체는 바뀌지 않는다
- **`02-4 Ingestion API`** — `result` 필드, 실패 코드, `previewImageUrl`, `img_index`, 429, §7 사진 재시도는 새 Key, `RecipeSource` 문장, 요청 제약(2048자·중복 금지), 예시 Key 형식
- **`03 시스템 설계` 상위 페이지** — 프로세스 분리, `RecipeSource` UNIQUE, `IngestionJobImage`, `bootstrap`
- **`03-1 전체 아키텍처`** — 맨 위 요약, §2·§3·§5(Worker 구조·선점), §8(재시도·backoff·단계별 timeout), §9(데이터 모델), §12~§13(사진 inline, Instagram), §14(진입점·SDK 표기), §15~§16(컨테이너 구성·SA 후속 조건). §10의 Notification Worker 문장은 Notification 설계에서 다룬다
- **`03-2 패키지·모듈 구조`** — §1·§3·§4의 `bootstrap`, `RecipeSource` 계열
- **`03-3 주요 Sequence Diagram`** — §1·§2 설명, §5(사진 연결 순서와 선언되지 않은 참여자), §6(재시도·선점), §7·§9·§10(RecipeSource, 만료 루프, 삭제 대상)
- **`04-3` 외부 예외 정규화** — 429를 조건 없이 재시도 대상으로 적은 문장
- **Notion API 명세 DB** — 분석 Job 생성·조회 행, 레시피 생성 행. 조회 성공 메시지, 400·404의 `data.code`, 예시 Key 형식, 레시피 생성 행의 개발 현황(`ingestionJobId` 경로는 아직 미구현)
- **이 저장소**
  - [Recipe Spec](./recipe.md): 한눈에 보기, §2.1, §3.1, §3.2, §3.3, §3.4, §4.3. 2단계 착수 전에 고친다
  - [Upload Spec](./upload.md): §3.1 "소유 도메인이 바뀌는 경우"
  - [Ingredient Spec](./ingredient.md): 사용처에 Ingestion 추가
