# 토큰 재발급 API 구현 및 테스트 결과

작성일: 2026-09-14

로그아웃 수정 후 전체 486개 테스트 통과를 먼저 확인한 다음 재발급 API를 구현했다. 최종 전체 테스트는 **498개 통과, 실패 0개, 오류 0개, 건너뜀 0개**다.

## API 계약

`POST /api/v1/auth/token/refresh`

- `Content-Type: application/json`
- Authorization 헤더는 필요 없다. 만료된 access token이 헤더에 포함되어도 정상 refresh token으로 재발급할 수 있다.
- 요청에는 소셜 제공자의 토큰이 아니라 이 서버가 로그인·회원가입·재발급 시 반환한 refresh token을 넣는다.

요청:

```json
{
  "refreshToken": "현재 저장된 서비스 refresh token"
}
```

성공 응답 — HTTP 200:

```json
{
  "status": 200,
  "message": "토큰이 재발급되었습니다.",
  "data": {
    "accessToken": "새 access token",
    "refreshToken": "새 refresh token"
  }
}
```

인증 실패 응답 — HTTP 401:

```json
{
  "status": 401,
  "message": "로그인 정보가 유효하지 않습니다. 다시 로그인해주세요.",
  "data": {
    "code": "REFRESH_TOKEN_INVALID"
  }
}
```

| 상황 | HTTP | data.code |
| --- | --- | --- |
| refreshToken 누락·null·공백 | 400 | REQUEST_VALIDATION_FAILED |
| 본문 누락 또는 JSON 형식 오류 | 400 | INVALID_REQUEST_FORMAT |
| 잘못된 서명·만료·잘못된 토큰 종류·필수 JWT claim 오류 | 401 | REFRESH_TOKEN_INVALID |
| DB 토큰 없음·해시 불일치·DB 만료·로그아웃·이미 사용한 토큰 | 401 | REFRESH_TOKEN_INVALID |
| 존재하지 않거나 삭제된 사용자 | 401 | REFRESH_TOKEN_INVALID |
| 내부 처리 실패 | 500 | INTERNAL_SERVER_ERROR |

필드 검증 실패에는 기존 공통 응답 규칙에 따라 `data.errors`에 필드명과 사유를 포함한다.

## 구현 내용

1. `TokenRefreshRequest`, `TokenRefreshResponse`를 추가하고 `AuthController`에 재발급 엔드포인트와 Swagger 설명을 추가했다. 기존 SecurityConfig에 예약되어 있던 공개 경로를 사용한다.
2. `JwtProvider.parseRefreshToken()`에서 서명과 만료를 검사하고 `type=refresh`, `exp`, 비어 있지 않은 `jti`, 양의 정수 `sub`를 요구한다. Access token과 signup token은 사용할 수 없다.
3. `RefreshTokenService.refresh()`는 사용자 행 잠금을 획득한 뒤 저장된 SHA-256 해시와 만료 시각을 검사한다. 잠금 대기 중 만료될 수 있으므로 JWT 만료도 다시 확인한다.
4. 성공 시 access/refresh token을 모두 새로 발급하고 저장된 해시와 만료 시각을 교체한다. DB에는 원문 refresh token을 저장하지 않는다. 만료 시각은 JWT의 실제 `exp`와 일치하도록 저장한다.
5. 조회·검증·교체를 하나의 트랜잭션에서 처리한다. 동일 토큰으로 동시 재발급하면 한 요청만 성공한다. 발급 실패 시 기존 토큰 상태를 보존한다.
6. 로그인·로그아웃·재발급이 같은 사용자 잠금을 사용한다. 로그아웃과 재발급이 동시에 실행되어도 두 작업이 끝난 뒤 refresh token이 남지 않는다.
7. 기존 `V13__create_refresh_tokens.sql`과 저장 엔티티를 활용한다. 이번 재발급 구현을 위한 추가 마이그레이션은 없다.

## 클라이언트 연동

1. Access token 만료로 인증 요청이 실패하면 저장한 refresh token으로 이 API를 호출한다.
2. 성공 응답의 **두 토큰을 함께 교체**하고 실패했던 요청을 새 access token으로 재시도한다.
3. 여러 API에서 동시에 401을 받아도 재발급 요청은 하나만 실행하고 다른 요청은 그 결과를 기다리도록 한다. 같은 이전 토큰을 동시에 보내면 나머지 요청은 401이다.
4. 재발급 API가 401이면 로컬 토큰을 제거하고 소셜 로그인부터 다시 진행한다. 재발급 API 자체를 무한 재시도하는 인터셉터를 만들지 않는다.
5. 서버에서 회전이 완료됐지만 네트워크 문제로 응답을 받지 못한 경우, 이전 refresh token을 다시 보내면 401이므로 재로그인이 필요할 수 있다. 이전 토큰 유예 기간은 두지 않았다.

## 유지한 정책과 적용 시 참고

- 사용자당 refresh token 1개: 재로그인 또는 재발급 이후 가장 최근 저장된 토큰만 유효하다. 기기별 독립 세션은 아니다.
- 현재 설정 기준 access token은 30분, refresh token은 14일이며 재발급 시 새 유효기간을 부여한다. 별도의 절대 세션 만료 기간은 추가하지 않았다.
- 로그아웃과 재발급은 기존 access token을 즉시 차단하지 않는다. 기존 access token은 만료까지 유효하다.
- 로그아웃은 유효한 access token이 필요하며 해당 사용자의 저장된 refresh token을 삭제한다. 클라이언트도 두 토큰을 제거해야 한다.
- 이미 사용한 refresh token을 다시 제출하면 401이며, 현재 정상 토큰까지 함께 폐기하지는 않는다.
- 이번 수정 이전에 발급되어 `jti`가 없는 refresh token은 사용할 수 없다. 해당 사용자는 재로그인해야 한다.
- 기존 `TIMESTAMP` 스키마를 유지하며 DB 만료 시각은 서버 로컬 시간대로 변환한다. 여러 서버를 운영할 경우 서버 시간대 설정을 일치시켜야 한다.

## 테스트

실행 명령:

```sh
./gradlew test --offline
```

최종 결과: **498개 테스트 통과 / 실패 0 / 오류 0 / 건너뜀 0**, `BUILD SUCCESSFUL`.
Java 및 테스트 코드 컴파일도 함께 통과했다. PostgreSQL Testcontainers와 MockMvc로 검증했으며 실제 소셜 계정이나 운영 DB를 호출하는 테스트는 수행하지 않았다. `.env` 비밀값은 수정하지 않았다.

추가한 `TokenRefreshApiTest` 12개 테스트의 검증 범위:

- 인증 헤더 없는 정상 재발급, 두 토큰 교체, 사용자 식별, 해시 저장 및 정확한 만료 시각.
- 이전 토큰 재사용 거부와 새 refresh token의 연속 재발급.
- 만료된 access token 헤더를 포함한 재발급 성공.
- 요청값 누락·공백·잘못된 JSON의 400 응답.
- 잘못된 토큰·다른 서명·만료·access/signup token의 401 응답.
- exp/jti 누락 및 sub 누락·문자열·0·음수의 401 응답.
- 미저장 토큰과 재로그인으로 교체된 토큰 거부, 현재 토큰 보존.
- JWT가 유효해도 DB 만료 시 거부.
- 없는 사용자 및 삭제된 사용자 거부.
- 로그아웃 이후 원래 토큰과 회전된 토큰 모두 거부.
- 발급 실패 시 500 응답, 기존 토큰 보존 및 이후 정상 재발급.
- 동일 토큰 동시 요청 6건에서 1건 성공·5건 401, 성공 토큰의 해시만 저장.
- 로그아웃·재발급 동시 실행 후 저장 토큰 없음.

초기 신규 테스트에서 삭제 사용자 상태가 다음 테스트에 남는 문제가 발견되어 `@AfterEach`에서 복원하도록 수정했다. 위 최종 결과에는 이 테스트 격리 수정도 포함된다.

로그아웃 수정 내역과 재발급 구현 전 검증 결과는 [FIX_DOCS.md](FIX_DOCS.md)에 기록했다.
