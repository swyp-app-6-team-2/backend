# Inquiry Tech Spec

> **문서 버전**: v1 · **기준일**: 2026-09-15

## 한눈에 보기

Inquiry는 사용자가 앱에서 남긴 문의를 받고, 운영자가 관리자 페이지에서 답변하게 한다.

| 질문 | 답변 |
|---|---|
| 무엇을 관리하는가? | 문의(유형·제목·내용·첨부 사진)와 그 답변 |
| 사용자는 무엇을 할 수 있는가? | 문의 접수, 최근 1년 내 문의 목록·상세 조회 |
| 운영자는 무엇을 할 수 있는가? | 관리자 페이지에 로그인해 전체 문의를 조회하고 답변을 작성·수정 |
| 어떤 API를 제공하는가? | 앱: 접수 POST, 목록 GET, 상세 GET. 관리자: 서버가 렌더링하는 HTML 페이지 |
| 핵심 데이터는 무엇인가? | `inquiry` |
| 어떤 도메인과 협력하는가? | [Upload](./upload.md)로 첨부 사진을 연결·조회하고, 관리자 조회에서 사용자 닉네임·로그인 수단을 읽는다 |
| 핵심 기술 결정은 무엇인가? | 답변을 문의 행에 두고 상태는 답변 유무로 계산한다. 관리자 페이지는 앱 API와 분리된 세션 로그인 필터 체인을 쓴다 |
| MVP에서 제외하거나 감수하는 것은? | 답변 알림(추후 추가), 문의 수정·삭제, 관리자 계정 여러 개, 로그인 시도 제한 |

## 1. 개요

```text
앱:     (사진이 있으면 업로드 URL 발급 → GCS 업로드) → 문의 접수 → 문의내역·상세 조회
관리자: /admin/login → 문의 목록 → 문의 상세 → 답변 저장
앱:     문의내역에서 상태가 답변완료로 바뀌고 상세에서 답변 확인
```

답변이 등록돼도 사용자에게 알림을 보내지 않는다. 사용자는 문의내역의 상태 배지로 답변 여부를 확인한다.

## 2. 목표와 범위

### 2.1. 목표

- 사용자가 유형·제목·내용과 사진 최대 5장으로 문의를 접수한다.
- 사용자가 최근 1년 내 자기 문의의 목록과 상세(답변 포함)를 본다.
- 운영자가 관리자 페이지에서 전체 문의를 상태·유형으로 걸러 보고, 답변을 작성·수정한다.

### 2.2. MVP 제외 범위

| 제외 | 이유 |
|---|---|
| 답변 알림(푸시·이메일) | 추후 추가 |
| 문의 수정·삭제, 답변 삭제 | 화면이 없다 |
| 문의 유형 조회 API | 유형은 이 문서의 목록을 앱이 보유한다 |
| 관리자 계정 여러 개, 답변자 기록 | 운영자가 팀 몇 명이라 계정 1개로 충분하다 |
| 로그인 시도 횟수 제한·계정 잠금 | 길고 무작위인 비밀번호로 대신한다 |
| 1년 지난 문의 삭제 | 조회에서만 제외한다 |
| 탈퇴 시 정리 | 탈퇴 방식이 정해지지 않았다 |

### 2.3. 도메인 협력

```text
Inquiry ── 첨부 사진 Key 연결·조회 URL 발급 ──▶ Upload
Inquiry ── 관리자 조회에서 닉네임·로그인 수단·탈퇴 여부 읽기 ──▶ users, profiles (읽기 전용)
Admin   ── 목록·상세·답변 저장 호출 ──▶ Inquiry
```

Account 도메인의 코드는 바꾸지 않는다. 관리자 조회 쿼리가 `users`·`profiles` 테이블을 읽기만 한다.

## 3. 기술 설계

### 3.1. 데이터 모델

migration `V23__create_inquiry.sql`.

**`inquiry`** — 문의 한 건과 그 답변

| 컬럼 | 타입 | 제약 |
|---|---|---|
| `id` | bigint identity | PK |
| `user_id` | bigint | NOT NULL, FK `users` |
| `type` | varchar(255) | NOT NULL, `ck_inquiry_type`: `RECIPE`/`SLOT`/`ACCOUNT`/`NOTIFICATION`/`BUG`/`ETC` |
| `title` | varchar(255) | NOT NULL |
| `content` | text | NOT NULL |
| `attachment_keys` | `text[]` | NOT NULL, 기본 `{}`, `ck_inquiry_attachment_keys`: `cardinality(attachment_keys) <= 5` |
| `answer` | text | NULL이면 답변 전 |
| `answered_at` | timestamptz | 처음 답변을 저장한 시각. 답변을 수정해도 바뀌지 않는다 |
| `created_at` | timestamptz | NOT NULL. 접수 시각 |

- `ck_inquiry_answer`: `answer`와 `answered_at`은 둘 다 NULL이거나 둘 다 NOT NULL이다.
- 인덱스: `idx_inquiry_user_id_created_at (user_id, created_at, id)`. 내 문의 목록의 조건과 `created_at DESC, id DESC` 정렬을 함께 처리한다.
- **답변을 같은 행에 두는 이유**: 답변은 문의당 1개이고 항상 문의와 함께 읽는다.
- **상태 컬럼이 없는 이유**: `answer`가 있으면 `ANSWERED`, 없으면 `RECEIVED`로 계산한다. 상태와 답변이 어긋나는 데이터가 생길 수 없다.
- **첨부 사진을 배열에 두는 이유**: 항상 문의와 함께 읽고 개별 조회·수정이 없다. 순서는 요청 순서를 그대로 저장한다. 같은 Key가 두 리소스에 쓰이는 것은 Upload의 일회성 연결이 막는다.

**`upload_object`** — 같은 migration에서 `ck_upload_object_purpose`를 지우고 `INQUIRY_ATTACHMENT`를 더해 다시 만든다. 저장소 경로 첫 구간은 `inquiry-attachments`다.

**문의 유형**

| 코드 | 표시 이름 | 해당 상황 |
|---|---|---|
| `RECIPE` | 레시피 | 링크·이미지 분석 실패, 저장·수정 오류, 요리 기록 |
| `SLOT` | 별 슬롯 확장 | 광고 시청 후 슬롯 미반영, 광고 오류, 결제·구매 복원·환불 |
| `ACCOUNT` | 계정·로그인 | 소셜 로그인 실패, 회원가입, 탈퇴 |
| `NOTIFICATION` | 알림 | 설정한 시간대에 식사 알림이 오지 않음, 알림 설정이 저장되지 않음 |
| `BUG` | 오류 신고 | 위에 해당하지 않는 앱 오작동 |
| `ETC` | 제안·기타 | 기능 제안, 그 밖의 문의 |

표시 이름은 앱이 보유한다. 서버는 코드만 주고받는다.

### 3.2. 사용자 API

| Method | Endpoint | 기능 |
|---|---|---|
| `POST` | `/api/v1/inquiries` | 문의 접수 |
| `GET` | `/api/v1/inquiries` | 내 문의 목록 |
| `GET` | `/api/v1/inquiries/{inquiryId}` | 내 문의 상세 |

모든 API는 인증이 필요하다. 첨부 사진은 기존 `POST /api/v1/uploads/images`에 `purpose: "INQUIRY_ATTACHMENT"`로 업로드 URL을 받아 올린다.

#### `POST /api/v1/inquiries`

```json
{
  "type": "SLOT",
  "title": "광고를 봤는데 슬롯이 안 늘어나요",
  "content": "오늘 오후 3시쯤 광고를 끝까지 봤는데 슬롯 개수가 그대로예요.",
  "attachmentKeys": ["inquiry-attachments/12/a3f2c1.jpg"]
}
```

- 응답 `201`, 메시지 `문의가 접수되었습니다.`, `data: {"inquiryId": 31}`
- `attachmentKeys`는 생략하거나 `null`이면 빈 배열로 처리한다.
- 전용 `Idempotency-Key`는 두지 않는다. 앱의 제출 확인 팝업이 중복 접수를 줄이고, 앱은 접수 POST를 자동 재시도하지 않는다.

| 요청 | 응답 |
|---|---|
| 본문 없음, JSON 형식 오류, 없는 `type` 값 | `400 INVALID_REQUEST_FORMAT` |
| `type` 누락, `title` 빈 값·공백·255자 초과, `content` 빈 값·공백·2,000자 초과, `attachmentKeys` 6개 이상·요소 빈 값·같은 Key 중복 | `400 REQUEST_VALIDATION_FAILED` + `data.errors`. 중복은 `field: "attachmentKeysUnique"` 항목으로 담는다 |
| 없거나, 다른 사용자에게 발급됐거나, 다른 용도로 발급됐거나, 실제로 업로드되지 않은 Key | `400 INQUIRY_ATTACHMENT_INVALID` |
| 이미 다른 리소스에 연결된 Key | `409 INQUIRY_ATTACHMENT_ALREADY_USED` |

Key 하나라도 연결에 실패하면 문의를 저장하지 않고, 앞서 연결한 Key도 함께 롤백한다.

#### `GET /api/v1/inquiries?page=0&size=20`

```json
{
  "status": 200,
  "message": "문의 목록을 조회했습니다.",
  "data": {
    "totalCount": 2,
    "inquiries": [
      {
        "inquiryId": 31,
        "type": "SLOT",
        "title": "광고를 봤는데 슬롯이 안 늘어나요",
        "content": "오늘 오후 3시쯤 광고를 끝까지 봤는데 슬롯 개수가 그대로예요.",
        "status": "ANSWERED",
        "createdAt": "2026-09-15T06:10:00Z"
      }
    ]
  }
}
```

- 최근 1년, 곧 `created_at >= 현재 시각을 Asia/Seoul 달력으로 1년 되돌린 시각`인 문의만 `created_at DESC, id DESC`로 준다. 정렬 파라미터는 없다.
- `page` 기본 0, `size` 기본 20. `page < 0` 또는 `size`가 1~100 밖이면 `400 REQUEST_VALIDATION_FAILED`.
- `totalCount`는 페이지 크기가 아니라 조건에 맞는 전체 수다.
- `content`는 전체를 준다. 미리보기 길이는 앱이 정한다. 첨부 사진과 답변은 목록에 넣지 않는다.
- 문의가 없으면 `totalCount: 0`, 빈 목록이다.

#### `GET /api/v1/inquiries/{inquiryId}`

```json
{
  "status": 200,
  "message": "문의를 조회했습니다.",
  "data": {
    "inquiryId": 31,
    "type": "SLOT",
    "title": "광고를 봤는데 슬롯이 안 늘어나요",
    "content": "오늘 오후 3시쯤 광고를 끝까지 봤는데 슬롯 개수가 그대로예요.",
    "attachmentImageUrls": ["https://storage.googleapis.com/..."],
    "status": "ANSWERED",
    "createdAt": "2026-09-15T06:10:00Z",
    "answer": "안녕하세요, 별따먹자입니다. 확인 후 슬롯을 반영해드렸어요.",
    "answeredAt": "2026-09-16T01:20:00Z"
  }
}
```

- 없거나, 다른 사용자의 문의이거나, 접수한 지 1년이 지난 문의는 모두 `404 INQUIRY_NOT_FOUND`. `inquiryId`가 숫자가 아니면 `400 INVALID_REQUEST_FORMAT`.
- 답변 전에는 `answer`·`answeredAt`이 `null`이다. 값이 없어도 키는 내려준다.
- `attachmentImageUrls`는 저장 순서대로 조회 URL(유효 60분)을 담는다. 서명에 실패한 사진은 배열에서 뺀다. 사진 한 장 때문에 상세 전체를 실패시키지 않는다.

#### 시각

모든 시각은 ISO 8601 UTC(`Z`)다. 화면의 한국 시각 표시는 앱이 계산한다.

### 3.3. 관리자 페이지

서버가 Thymeleaf로 렌더링하는 HTML이다. 앱 API와 같은 호스트의 `/admin` 경로에서 제공한다(dev: `https://dev-api.starpick.cloud/admin`).

| 화면 | 경로 | 내용 |
|---|---|---|
| 진입 | `GET /admin` | `/admin/inquiries`로 리다이렉트 |
| 로그인 | `GET /admin/login` | 아이디·비밀번호. 실패하면 `?error`로 돌아와 "아이디 또는 비밀번호가 올바르지 않습니다.", 세션이 만료돼 돌아오면 `?expired`로 "세션이 만료됐어요. 다시 로그인해 주세요." |
| 문의 목록 | `GET /admin/inquiries?status=&type=&page=` | 번호, 접수 시각, 유형, 제목, 작성자 닉네임, 상태. `created_at DESC, id DESC`, 한 페이지 20건 |
| 문의 상세 | `GET /admin/inquiries/{inquiryId}` | 작성자(사용자 ID·닉네임·마지막 로그인 수단), 유형, 제목, 내용, 접수 시각, 첨부 사진, 답변 시각, 답변 입력칸 |
| 답변 저장 | `POST /admin/inquiries/{inquiryId}/answer` | 성공하면 상세로 리다이렉트하고 "답변을 저장했습니다." |

- `status`는 `RECEIVED`/`ANSWERED`, `type`은 유형 코드다. 비우면 전체다. `page`는 0부터 시작하고 기본 0이다.
- 없는 `status`·`type` 값, 음수이거나 숫자가 아닌 `page`, 숫자가 아닌 `inquiryId`는 `400` "잘못된 요청입니다." 화면을 보여준다.
- 관리자 목록·상세에는 1년 제한이 없다.
- 작성자가 탈퇴했으면(`users.deleted_at` 있음) "탈퇴한 사용자"로 표시한다. 프로필이 없으면 닉네임을 비워 둔다.
- 첨부 사진은 문의 작성자 기준으로 조회 URL을 발급하고, 누르면 새 탭에서 원본을 연다.
- 답변은 공백만 있거나 2,000자를 넘으면 저장하지 않고, 입력한 내용을 유지한 채 오류 문구를 보여준다.
- 이미 답변이 있으면 입력칸에 채워 보여주고, 저장하면 답변을 교체한다. `answered_at`은 첫 저장 시각을 유지한다.
- 없는 문의는 `404` "문의를 찾을 수 없습니다." 화면, 그 밖의 오류는 `500` "오류가 발생했습니다." 화면을 보여준다. 관리자 컨트롤러에만 적용되는 예외 처리기를 앱 API의 전역 처리기보다 먼저 두어 JSON 오류 응답이 나가지 않게 한다.
- 오류 화면은 관리자 전용 템플릿으로 만든다. Spring Boot 기본 오류 템플릿(`templates/error.html` 등)은 두지 않는다. 두면 앱 API 쪽 오류에도 쓰인다.
- 제목·내용·닉네임·답변 같은 사용자 입력은 `th:text`·`th:field`로만 출력한다. `th:utext`는 쓰지 않는다. 줄바꿈은 CSS `white-space: pre-wrap`으로 표시한다.
- 시각은 `Asia/Seoul` 기준 `yyyy.MM.dd HH:mm`으로 표시한다.
- 화면은 앱과 같은 색·글꼴(Pretendard)을 쓴다. 글꼴은 jsDelivr CDN에서 불러오고, 불러오지 못하면 시스템 한글 글꼴로 보인다.

### 3.4. 보안

앱 API의 JWT 필터 체인과 별도로 `/admin/**`에만 적용되는 필터 체인을 별도 설정 클래스에 둔다. 기존 체인 설정은 바꾸지 않는다.

| 항목 | 설정 |
|---|---|
| 적용 경로 | `securityMatcher("/admin/**")` |
| 순서 | `@Order(-1)`. 모든 요청을 받는 앱 API 체인(`@Order(1)`)보다 먼저 검사해야 한다 |
| 공개 경로 | `GET·POST /admin/login`. 나머지 `/admin/**`는 인증 필요 |
| 인증 | 폼 로그인. `POST /admin/login`, 성공하면 원래 가려던 주소와 무관하게 항상 `/admin/inquiries`로 이동한다 |
| 로그아웃 | `POST /admin/logout`, 로그인 화면으로 이동 |
| 세션 | 서버 메모리 세션, 요청 없이 30분이면 만료. 만료된 세션으로 요청하면 `/admin/login?expired`로 보낸다 |
| CSRF | 켠다. 모든 POST 폼에 토큰이 들어간다. 토큰이 없거나(세션 만료) 맞지 않으면(다른 탭에서 다시 로그인) `/admin/login?expired`로 보낸다 |
| 계정 | `ADMIN_USERNAME`·`ADMIN_PASSWORD` 환경변수. 기본값이 없어 누락되면 기동에 실패한다 |
| 비밀번호 | 환경변수에는 원문을 두고, 기동 시 BCrypt로 해시해 메모리에 보관한다. BCrypt 입력 제한 때문에 **72바이트 이하**여야 한다. `openssl rand -hex 32` 결과(64자)를 쓴다. compose가 따옴표 없는 값의 `$`를 치환하고 Spring이 `${`·`#{`를 다시 해석해 특수문자가 조용히 잘리기 때문이다 |

- 관리자 계정은 `UserDetailsService` Bean으로 등록한다. Bean이 없으면 Spring Boot가 쓰지 않는 기본 계정을 만들고 기동 로그에 임시 비밀번호 경고를 남긴다. 앱 API 체인은 폼 로그인·HTTP Basic이 꺼져 있어 이 계정으로 인증되는 경로가 없다.
- API는 세션·쿠키를 쓰지 않으므로 관리자 세션 쿠키와 겹치지 않는다.
- 환경변수에 해시가 아니라 원문을 두는 이유: 기존 비밀값(`JWT_SECRET` 등)과 같은 방식이고, BCrypt 해시의 `$`를 docker compose 설정에서 이스케이프하다 실수하기 쉽다.
- **프록시 뒤 HTTPS 인식**: TLS는 Caddy가 끝내고 앱은 http로 받는다. `server.forward-headers-strategy: native`로 `X-Forwarded-Proto`를 받아들여, 앱이 요청을 https로 인식하게 한다. 그래야 세션 쿠키에 `Secure`가 붙고 로그인 리다이렉트가 https 주소로 나가며, `request.getRemoteAddr()`가 실제 접속 IP가 된다. 로컬 http 개발에서는 `Secure`가 붙지 않아 그대로 로그인할 수 있다.

### 3.5. 트랜잭션과 동시성

| 작업 | 경계 | 동시성 |
|---|---|---|
| 문의 접수 | `@Transactional` 하나: 요청 순서대로 Key마다 Upload 연결 → `inquiry` 저장 | Key 연결은 Upload의 조건부 UPDATE라, 같은 Key를 동시에 연결해도 한 요청만 성공한다 |
| 내 목록 | 읽기 트랜잭션 | — |
| 내 상세 | 문의 조회는 짧은 읽기 트랜잭션, 조회 URL 서명은 트랜잭션 밖 | — |
| 관리자 목록·상세 | 조회는 읽기 트랜잭션, 조회 URL 서명은 트랜잭션 밖 | — |
| 답변 저장 | UPDATE 한 문장: `answer = :answer, answered_at = coalesce(answered_at, :now)`. 0행이면 없는 문의 | 행 잠금 없음. 두 관리자가 동시에 저장하면 나중 저장이 답변을 덮고, `answered_at`은 첫 시각을 유지한다 |

문의 접수의 Key 연결은 저장소 존재 확인 호출을 트랜잭션 안에서 한다. Recipe 대표 이미지 연결과 같은 방식이며, 저장소 확인에 실패하면 전체를 롤백하고 500을 반환한다.

### 3.6. 실행 구조와 설정

- 의존성 `org.springframework.boot:spring-boot-starter-thymeleaf`를 추가한다.
- 관리자 계정 환경변수는 다음 네 곳에 함께 둔다. 하나라도 빠지면 그 환경에서 앱이 뜨지 않는다.

| 위치 | 값 |
|---|---|
| `src/main/resources/application.yml` | 아래 `admin` 블록 |
| `src/test/resources/application.yml` | 테스트용 고정값. 이 파일은 main 설정을 병합하지 않고 대체한다 |
| `docker-compose.vm.yml` app `environment` | `ADMIN_USERNAME`·`ADMIN_PASSWORD` 전달 |
| `.env.vm.example` | 빈 항목 |

```yaml
server:
  # Caddy 뒤에서 https·실제 접속 IP를 인식한다.
  forward-headers-strategy: native

admin:
  # 기본값을 두지 않는다. 누락을 fail-fast 로 잡는다.
  username: ${ADMIN_USERNAME}
  password: ${ADMIN_PASSWORD}
```

### 3.7. 로깅

| 상황 | 레벨 | 남기는 것 |
|---|---|---|
| 관리자 로그인 실패 | WARN | 접속 IP |
| 관리자 페이지에서 처리하지 못한 예외 | ERROR + stack | `method`, `path` |

- 관리자 페이지의 예외는 앱 API 전역 처리기를 거치지 않으므로, 관리자 예외 처리기가 같은 형식으로 직접 남긴다. 없는 문의(404)와 잘못된 요청(400)은 남기지 않는다.
- query string, 폼 본문, 헤더는 남기지 않는다. 문의 제목·내용, 답변, 첨부 사진 Key, 로그인에 입력한 아이디·비밀번호도 남기지 않는다.
- 예외: 조회 URL 서명에 실패하거나 소유자가 맞지 않으면 Upload 공통 코드가 WARN에 `objectKey`를 남긴다. 모든 도메인이 쓰는 Upload 규칙이라 여기서 바꾸지 않는다.
- 앱의 문의 접수·조회는 별도 로그를 남기지 않는다.

## 4. 받아들이는 한계

| 상황 | 결과 |
|---|---|
| 답변이 등록돼도 사용자가 문의내역을 열지 않음 | 답변을 모른다 |
| 배포로 앱이 재시작됨 | 관리자가 다시 로그인해야 한다 |
| 답변을 쓰는 사이 세션이 만료되거나 다른 탭에서 다시 로그인한 뒤 저장 | 로그인 화면으로 이동하고 입력한 답변은 사라진다 |
| 로그인 무차별 대입 시도 | 막는 장치가 없다. 비밀번호 강도에 의존하고, 실패는 WARN 로그로 확인한다 |
| 컨트롤러가 없는 `/admin/...` 주소 | 로그인 후 앱 API와 같은 JSON 404가 나간다 |
| 사진을 올린 뒤 접수를 취소함 | 연결되지 않은 사진이 저장소에 남는다(Upload 공통 한계) |
| 두 관리자가 같은 문의에 동시에 답변 | 나중 저장이 앞 답변을 덮는다 |

## 5. 미정 사항

| 항목 | 결정 주체 | 막는 것 |
|---|---|---|
| 접수 완료 화면 문구("확인 후 이메일로 답변 드릴게요") 교체 | 기획·디자인 | 없음 |
| 문의 유형 표시 이름과 드롭다운 항목 6개 반영 | 기획·디자인 | 없음 |
| prod 도메인 | 팀 | 없음. 관리자 페이지는 도메인과 무관하다 |
| 탈퇴 방식과 Inquiry 정리 | 팀 | 탈퇴 구현 |
