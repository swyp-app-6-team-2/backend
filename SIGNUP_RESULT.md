# 회원가입 API 구현 결과

작성일: 2026-09-14

## 기준 및 확정 사항

첨부한 `회원가입_API.pdf`의 POST `/api/v1/auth/signup` 명세와 약관 동의 UI를 기준으로 구현했다.
PDF의 성공 200, 필수 약관 미동의 400, 가입 토큰 오류 401 응답 형식을 유지한다.

UI에만 있던 만 14세 이상 확인은 사용자 확인을 받아 **`ageOver14Agreed` 필수 필드**로 추가했다.
프런트엔드 요청과 기존 API 명세에도 이 필드를 반영해야 한다.
이메일은 이전에 확정한 대로 선택 정보다.

## API 요청

```http
POST /api/v1/auth/signup
Content-Type: application/json
```

로그인 accessToken/Authorization 헤더가 필요하지 않다. 소셜 로그인 응답의 signupToken을 본문에 넣는다.

```json
{
  "signupToken": "소셜 로그인 API에서 발급받은 signupToken",
  "ageOver14Agreed": true,
  "serviceTermsAgreed": true,
  "privacyAgreed": true,
  "marketingAgreed": false,
  "serviceAgreed": false
}
```

| 필드 | 규칙 |
| --- | --- |
| signupToken | 필수 문자열. 서버 서명, 만료 시간, type, 소셜 식별 정보를 검증 |
| ageOver14Agreed | 필수, true만 허용. 만 14세 이상 확인 |
| serviceTermsAgreed | 필수, true만 허용. 서비스 이용약관 |
| privacyAgreed | 필수, true만 허용. 개인정보 동의 |
| marketingAgreed | 필드를 반드시 전달해야 하지만 false 허용. 마케팅 정보 수신 |
| serviceAgreed | 필드를 반드시 전달해야 하지만 false 허용. 서비스 알림 수신 |

‘선택 동의’는 **동의하지 않아도 가입 가능**하다는 뜻이다. PDF의 Required=O에 맞춰 선택 동의 여부도 요청에는 명시적으로 전달해야 한다.
이 API에서 provider, socialUid, email, userId를 입력받지 않는다. 신원 정보는 검증된 signupToken에서만 읽는다.

## 응답

### 200: 회원가입 성공

```json
{
  "status": 200,
  "message": "회원가입이 완료되었습니다.",
  "data": {
    "userId": 1,
    "accessToken": "access-token...",
    "refreshToken": "refresh-token..."
  }
}
```

### 400: 필수 동의 누락/false/null

```json
{
  "status": 400,
  "message": "필수 약관에 동의해야 합니다.",
  "data": null
}
```

`ageOver14Agreed`, `serviceTermsAgreed`, `privacyAgreed` 중 하나라도 true가 아니면 동일하게 거부한다.
signupToken 누락/공백, 선택 동의 필드 누락/null은 기존 공통 Bean Validation 응답인 400 `REQUEST_VALIDATION_FAILED`다.
깨진 JSON이나 잘못된 요청 형식은 기존 공통 400 `INVALID_REQUEST_FORMAT` 응답을 사용한다.

### 401: 잘못되거나 만료된 signupToken

```json
{
  "status": 401,
  "message": "회원가입 인증 정보가 유효하지 않습니다. 다시 로그인해주세요.",
  "data": null
}
```

accessToken 또는 refreshToken을 signupToken 대신 넣어도 거부한다.
클라이언트는 소셜 로그인부터 다시 진행해 새 signupToken을 받아야 한다.

### 409: 중복 가입

```json
{
  "status": 409,
  "message": "이미 가입된 계정입니다. 다시 로그인해주세요.",
  "data": null
}
```

PDF에 없던 중복 요청 처리 규칙을 추가했다. 이미 연결된 `provider + socialUid`로는 추가 회원을 생성하지 않는다.
가입 버튼 중복 클릭, 유효한 동일 토큰 재전송, 같은 계정에 대한 동시 가입 요청에도 적용한다.
이미 가입한 계정의 약관 동의 값을 덮어쓰거나 signupToken으로 로그인 토큰을 재발급하지 않는다.
응답 유실 후 재시도로 409를 받으면 소셜 로그인 API를 다시 호출하면 된다.

## 처리 흐름과 저장 데이터

1. 필수 약관 3개가 모두 true인지 검사한다.
2. signupToken의 서명과 만료, `type=signup`, provider, socialUid를 검증한다. 이메일은 없어도 된다.
3. DB 트랜잭션에서 같은 소셜 계정의 가입 이력을 확인한다.
4. User에 약관 동의, 알림 동의, 가입 완료 시각, 최초 로그인 제공자/시각을 저장한다.
5. SocialCredential에 User와 provider/socialUid 및 선택 이메일을 연결한다.
6. 로그인 accessToken/refreshToken을 생성한다.
7. 전체 커밋 후 200 응답을 반환한다.

가입 과정에서는 외부 소셜 서버를 다시 호출하지 않는다. 소셜 인증은 이전 `/social-login` 단계에서 끝났으며, 서버가 발급한 임시 토큰으로 이어간다.

**원자성:** User 저장, SocialCredential 저장, 토큰 생성 중 어느 단계에서 예외가 발생해도 DB 작업을 모두 롤백한다.
**동시성:** 사전 중복 확인 외에 DB의 `uk_provider_social_uid` UNIQUE 제약으로 경쟁 상태를 막는다. 이 제약 위반만 409로 변환하며 무관한 DB 오류를 중복 가입으로 숨기지 않는다.
**식별:** 이메일로 회원을 합치지 않는다. 같은 이메일이라도 서로 다른 소셜 계정이면 별개 회원이다.

Profile은 이번 요청 필드에 닉네임/프로필 이미지나 기본값 정책이 없어 생성하지 않는다. User와 SocialCredential 생성만으로 현재 소셜 로그인 및 회원 소유 API를 사용할 수 있다. 향후 프로필 설정 API 또는 기본 프로필 정책이 정해지면 별도로 연결한다.

## DB 변경

새 마이그레이션: `src/main/resources/db/migration/V12__add_signup_service_consent.sql`

| users 컬럼 | 저장 규칙 / 기존 회원 처리 |
| --- | --- |
| service_agreed | 서비스 알림 동의. 신규 요청의 값을 저장하며 기존 회원 기본값은 false |
| service_agreed_at | 동의 시 가입 시각, 미동의 시 null |
| age_over_14_agreed | 신규 회원은 true. 기존 회원은 확인 이력이 없으므로 null |
| age_over_14_agreed_at | 신규 회원 확인 시각. 기존 회원은 null |

기존 `marketingAgreedAt`도 true일 때만 가입 시각으로 저장한다.
이용약관/개인정보 동의는 기존 필드에 저장하며 `signupCompletedAt`과 함께 최초 가입 동의 이력을 나타낸다.
약관 버전 관리나 개정 시 재동의는 이번 구현에 포함하지 않는다.

이메일 선택 처리의 V9 마이그레이션은 이전 소셜 로그인 작업에서 추가된 파일을 그대로 사용한다.
V1~V9를 변경하지 않았으며, V10은 앱 시작 시 Flyway가 적용한다. 이번 테스트는 격리된 테스트 DB에서 진행했으며 실제 개발/운영 DB를 직접 변경하지 않았다.

## UI 연동

- **신규 소셜 사용자:** `/social-login`의 `requiresTermsAgreement=true`이면 signupToken을 임시 보관하고 약관 화면으로 이동한다.
- **약관 전체 동의:** UI가 5개 동의 값을 함께 변경한다. 서버에는 `agreeAll` 필드를 보내지 않는다.
- **필수 미동의 인라인 경고:** 클라이언트가 선택하지 않은 필수 항목을 검사해 해당 줄에 표시한다. 서버도 가입을 차단한다. PDF의 `data:null` 계약을 유지하므로 서버가 미동의 필드 목록을 반환하지는 않는다.
- **가입 성공:** 응답의 로그인 토큰을 보관하고 신규 사용자용 후속 화면으로 진행한다.
- **기존 가입자 재로그인:** 기존 `/social-login`이 `requiresTermsAgreement=false`와 로그인 토큰을 반환한다. 프런트엔드는 온보딩·약관·알림 설정 화면을 건너뛰고 홈으로 이동한다. 기존 회원에게 새 ageOver14Agreed 동의를 강제로 요구하지 않는다.
- **소셜 인증 실패:** 이전 소셜 로그인 API의 401/502 응답을 사용한다. ‘로그인에 실패했어요’ 화면, 동일 제공자로 재시도, 다른 로그인 방법 선택은 프런트엔드가 처리한다. 회원가입 API 자체는 소셜 서버를 호출하지 않는다.
- **OS 알림 권한:** serviceAgreed 저장과 운영체제의 알림 권한 허용은 별개다. 이 API가 기기의 알림 권한을 변경하지 않는다.

백엔드 프로젝트이므로 실제 UI 화면/네비게이션 코드는 수정하지 않았다.

## 변경 파일

- `domain/auth/dto/SignupRequest.java`, `SignupResponse.java`: 요청/응답 계약.
- `domain/auth/service/SignupService.java`: 검증, 가입 저장, 중복/동시성 처리.
- `domain/auth/exception/SignupException.java`: 명세의 400/401 및 추가 409 응답.
- `domain/auth/controller/AuthController.java`: signup 경로와 Swagger 명세 추가.
- `global/security/jwt/JwtProvider.java`: signupToken 검증 및 SignupIdentity 추가.
- `global/GlobalExceptionHandler.java`: signup 오류 응답 처리.
- `domain/user/entity/User.java`: 서비스 알림/만 14세 이상 동의 필드 추가.
- `db/migration/V12__add_signup_service_consent.sql`: DB 컬럼 추가.
- `domain/auth/controller/SignupApiTest.java`: 회원가입 통합/동시성 테스트 추가.

Java 경로는 `src/main/java/com/star_pick/starpick/`, 테스트 경로는 `src/test/java/com/star_pick/starpick/`, DB 경로는 `src/main/resources/` 기준이다.

## 테스트

PostgreSQL Testcontainers와 실제 Spring HTTP 처리/트랜잭션을 사용했다. 테스트 전용 JWT 키를 쓰며 `.env`의 실제 비밀값은 사용하지 않는다.

검증 항목:

- Google/Kakao/Naver/Apple 모두 이메일 없이 가입 성공.
- 선택 동의 false로 가입 가능, true일 때 동의 시각 저장.
- 만 14세 이상·이용약관·개인정보 각 필드의 false/null/누락 차단.
- 선택 동의 필드와 signupToken 누락/null 차단.
- 잘못된 토큰, 만료 토큰, 다른 키로 서명된 토큰, access/refresh token 거부.
- 필수 claim/만료 누락, 잘못된 provider 및 claim 타입 거부.
- 동일 계정 재가입은 409이며 회원 수와 기존 동의를 유지.
- 같은 이메일을 가진 서로 다른 소셜 계정을 임의로 합치지 않음.
- 본문의 위조 provider/socialUid/email/userId 대신 검증된 토큰만 사용.
- 토큰 발급 중 예외가 발생하면 User와 SocialCredential 저장 모두 롤백.
- 6개 동시 가입 요청에서 정확히 1개 성공, 5개 409, 회원/소셜 계정 각 1개 생성.
- 신규 소셜 로그인 → signupToken → 회원가입 → 재로그인 시 약관 생략 응답.

검증 결과:

- 회원가입 테스트만 실행: `./gradlew test --offline --tests 'com.star_pick.starpick.domain.auth.controller.SignupApiTest'` — 통과.
- 추가 시나리오 반영 후 전체 실행: `./gradlew test --offline` — **411개 통과, 실패 0개, 오류 0개, 건너뜀 0개**.
- 전체 411개 중 새 회원가입 테스트는 **19개**다. 파라미터화한 제공자/필드별 실행을 포함한 수치다.
- `git diff --check` — 통과.

## 수동 연동 확인 순서

1. 기존 소셜 로그인 설정과 PostgreSQL을 준비하고 애플리케이션을 실행한다. Flyway V9/V10 적용을 확인한다.
2. 실제 소셜 로그인 후 `/api/v1/auth/social-login`에서 신규 사용자용 signupToken을 받는다.
3. 위 회원가입 요청을 보내 200과 userId/accessToken/refreshToken을 확인한다.
4. 같은 요청을 다시 보내 409인지 확인한다.
5. 같은 소셜 계정으로 `/social-login`을 다시 호출해 `requiresTermsAgreement=false`인지 확인한다.
6. 프런트엔드에서 필수 미동의 경고, 만료 시 재로그인, 기존 사용자 홈 직행을 확인한다.

실제 소셜 계정/모바일 UI를 이용한 수동 테스트는 수행하지 않았다. 회원탈퇴, refresh token 재발급, 약관 개정 재동의는 별도 기능이다.
