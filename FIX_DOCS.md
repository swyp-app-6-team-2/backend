# 소셜 로그인 수정 내역

작성일: 2026-09-14

## 구현 범위

소셜 로그인 오류 수정, 이메일 선택 처리, Apple 로그인 클라이언트를 구현했다.
회원가입·회원탈퇴·토큰 재발급 API는 이번 범위에 포함하지 않는다.

기존 API `POST /api/v1/auth/social-login`과 응답 구조를 유지한다.

- 기존 회원: `requiresTermsAgreement: false`, `userId`, `accessToken`, `refreshToken` 반환.
- 신규 사용자: `requiresTermsAgreement: true`, `signupToken` 반환. 회원 데이터를 생성하지 않는다.
- 회원 식별 기준은 `provider + socialUid`다. 이메일로 계정을 찾거나 합치지 않는다.

## 1. 공통 로그인 수정

### 기존 회원 조회와 갱신 트랜잭션

`SocialLoginService`에서 외부 소셜 토큰 검증을 마친 뒤 `TransactionTemplate`으로 DB 작업을 묶었다.
기존 `SocialCredential.user`의 LAZY 로딩 예외를 방지하고 `lastLoginProvider`, `lastLoginAt`을 변경 감지로 저장한다.
외부 API를 기다리는 동안 DB 트랜잭션을 유지하지 않는다.

### 요청 검증 및 오류 구분

- `provider`, `authToken`에 `@NotBlank`, 컨트롤러에 `@Valid` 적용.
- 누락/null/공백 또는 지원하지 않는 provider: `400 REQUEST_VALIDATION_FAILED`.
- provider는 앞뒤 공백 제거 후 `Locale.ROOT`로 대문자 변환한다.
- 유효하지 않은 소셜 토큰: 기존 `401` 응답 유지.
- 소셜 인증 서버 통신/공개 키 조회 장애: 기존 `502` 응답 유지.
- Google: 토큰 파싱을 서명 검증과 분리했다. 잘못된 JWT 구조·JSON·필수 시간/사용자 식별값 누락은 401이며, 공개 키 조회 IOException은 502다.
- Naver: 응답 성공 코드뿐 아니라 사용자 `id`의 null/공백도 검사한다.

### 카카오 앱 ID 검증

사용자 정보 조회 전에 `/v1/user/access_token_info`를 호출한다.
`app_id`가 설정된 `KAKAO_APP_ID`와 일치하고, 만료 시간이 양수인지 확인한다.
이후 `/v2/user/me`의 회원번호와 토큰 정보의 회원번호가 같은지도 검사한다.
다른 앱의 토큰이나 일치하지 않는 회원번호는 401로 거부한다.

### JWT 서명 키

`application.yml`의 공개된 기본 키를 제거하고 `${JWT_SECRET}`으로 변경했다.
값이 없으면 시작에 실패한다. 충분히 긴 무작위 비밀값을 환경에 설정해야 한다(UTF-8 기준 최소 32바이트).
이전 공개 키를 공유 환경에서 사용했다면 새 키로 교체해야 한다. 교체하면 기존 키로 발급한 토큰은 더 이상 검증되지 않는다.
실제 환경의 비밀값은 이 작업에서 생성하거나 변경하지 않았다.

## 2. 이메일 선택 처리

- `SocialCredential.email`의 `nullable = false`를 제거했다.
- `V9__make_social_credential_email_optional.sql`에서 기존 컬럼의 NOT NULL 제약을 해제한다.
- 적용된 V1~V8 파일은 수정하지 않는다. Flyway가 다음 시작 시 V9를 적용한다.
- 카카오 `kakao_account`가 없어도 유효한 회원번호가 있으면 로그인한다. 이메일은 null로 전달한다.
- Google, Naver, Apple도 이메일 없이 로그인할 수 있다.
- 이메일이 없으면 signupToken의 `email` claim은 생략된다. 이후 회원가입 구현도 이 claim을 필수로 요구하지 않아야 한다.
- 이메일은 선택적인 부가 정보다. 계정 연결이나 사용자 인증의 근거로 사용하지 않는다.

## 3. Apple 로그인

### 클라이언트가 보내는 값

```json
{
  "provider": "APPLE",
  "authToken": "Apple에서 발급받은 identityToken"
}
```

`authToken`은 Apple **ID token(identityToken)**이다. authorizationCode나 Apple access token을 보내는 방식이 아니다.
Apple SDK의 identityToken이 바이트 데이터라면 UTF-8 문자열로 변환해 전달한다.

Apple 인증 요청에 nonce를 사용했다면, Apple에 전달한 nonce 값도 보낸다.

```json
{
  "provider": "APPLE",
  "authToken": "Apple identityToken",
  "nonce": "Apple 인증 요청에 실제로 전달한 nonce"
}
```

SDK 연동에서 nonce를 SHA-256으로 해시해 Apple에 보냈다면, 여기에도 그 해시값을 보낸다.
토큰에 nonce가 있거나 요청에 nonce를 보냈으면 두 값이 정확히 일치해야 한다. 누락/불일치는 401이다.
nonce를 사용하지 않은 인증은 이 필드를 생략한다. 다른 제공자의 기존 요청에는 nonce를 추가할 필요가 없다.

이 비교는 서버가 발급하고 일회성으로 소비하는 challenge를 구현한 것은 아니다.
클라이언트도 인증 시도마다 nonce를 새로 만들고 해당 인증 요청과 응답을 연결해야 한다.
현재 API는 Google과 마찬가지로 유효기간 내 ID token을 받는 구조이며 서버에서 토큰을 일회성으로 소비하지 않는다.

### 서버 검증

- Apple의 고정 JWKS 주소 `https://appleid.apple.com/auth/keys`에서 RSA 공개 키 조회.
- `kid`로 키 선택. 토큰에 들어 있는 임의의 키 조회 URL은 사용하지 않음.
- `RS256` 서명 검증. 다른 알고리즘·잘못된 서명·알 수 없는 키 거부.
- issuer가 `https://appleid.apple.com`인지 확인.
- audience가 설정한 `APPLE_CLIENT_ID`를 포함하는지 확인.
- 만료 시간 필수 및 만료 검증, 비어 있지 않은 `sub` 필수.
- nonce를 사용한 요청은 nonce 일치 검증.
- 검증 완료된 `sub`를 socialUid로 사용. email은 선택적으로 읽음.

공개 키는 프로세스별 1시간 캐시한다. 새 kid가 나타나면 갱신을 시도하되, 임의 kid를 반복 요청해 Apple 호출을 유발하지 못하도록 갱신 간격을 최소 1분으로 제한한다.
따라서 키 갱신 직후 또 새 kid가 나타나면 다음 갱신까지 최대 약 1분 동안 해당 토큰이 거부될 수 있다.
공개 키 호출은 연결 2초, 읽기 3초 타임아웃을 사용한다. 조회 장애는 502이고, 정상 조회 결과에 키가 없는 경우는 401이다.

Apple ID token 검증에는 Apple 개인 키(.p8), Team ID, client_secret이 필요하지 않다.
향후 authorizationCode 교환·토큰 폐기·회원탈퇴를 구현할 때 필요한 별도 설정과 혼동하지 않는다.

## 실행 환경 설정

기존 환경변수에 다음 값을 설정한다. 실제 값은 저장소에 커밋하지 않는다.

| 변수 | 설정값 |
| --- | --- |
| `JWT_SECRET` | 서비스 JWT 서명용 무작위 비밀값. 최소 32바이트 |
| `GOOGLE_CLIENT_ID` | 현재 Google ID token의 audience에 해당하는 Client ID |
| `KAKAO_APP_ID` | 카카오 개발자 콘솔의 숫자 앱 ID. REST API 키가 아님 |
| `APPLE_CLIENT_ID` | 검증할 ID token의 audience. 네이티브 iOS는 해당 Bundle ID, 웹은 해당 Services ID |

Apple 설정은 하나의 Client ID를 허용한다. 서로 다른 audience를 쓰는 웹/iOS를 함께 지원하려면 허용 목록으로 확장해야 한다.
Apple Developer 설정과 클라이언트의 Sign in with Apple 설정도 실제 앱에 맞게 완료해야 한다.
이번 작업에서는 개발자 콘솔이나 클라이언트 앱을 변경하지 않았다.

`docker-compose.vm.yml`, `.env.vm.example`에 새 환경변수 전달/예시를 추가했다.
로컬 실행도 `.env` 또는 실행 환경에 값을 넣어야 한다. 테스트는 테스트 전용 더미 설정을 사용한다.

## 검증

소셜 로그인 전용 테스트를 추가했다.

- HTTP 필수값 검증, 잘못된 provider, 401/502 응답.
- 모든 provider의 신규 사용자 분기와 회원 미생성.
- 실제 PostgreSQL에서 이메일 null 저장, 기존 회원 로그인, 로그인 기록 영속화.
- 외부 소셜 조회 시 DB 트랜잭션이 열려 있지 않은지 확인.
- Google 잘못된 토큰 파싱/시간 claim 누락/검증 실패/통신 장애/이메일 누락.
- Kakao 앱 ID 불일치/회원번호 불일치/계정 정보 누락/만료/서버 장애.
- Naver 이메일 누락/추가 응답 필드/회원번호 누락.
- Apple 실제 생성한 RSA 키로 서명 성공·위조 서명·issuer/audience/만료/sub/알고리즘/nonce 검증.
- Apple JWKS 파싱, 캐시, 키 갱신, 잘못된 키와 서버 장애 처리.

외부 소셜 서비스는 테스트 대역을 사용한다. 실제 Google/Kakao/Naver/Apple 계정과 발급 토큰을 이용한 연동 확인은 별도로 필요하다.

실행: `./gradlew test --offline`

결과: **전체 392개 테스트 통과, 실패 0개, 건너뜀 0개**. 이 중 새 소셜 로그인 테스트는 35개다.
`git diff --check`도 통과했다.

## 참고 문서

- [Apple: Verifying a user](https://developer.apple.com/documentation/signinwithapple/verifying-a-user)
- [Kakao: REST API 및 액세스 토큰 정보 조회](https://developers.kakao.com/docs/ko/kakaologin/rest-api#access-token-info)
- [Google: 서버에서 ID token 검증](https://developers.google.com/identity/gsi/web/guides/verify-google-id-token)
