# 로그아웃 및 토큰 저장 버그 수정

작성일: 2026-09-14

요청에 따라 이전 문서 내용을 교체하고 이번 로그아웃 수정만 기록한다.

## 수정 내용

1. **동일 토큰 반복 발급 방지**: `JwtProvider.generateToken()`에 발급마다 UUID `jti`를 추가했다. 동일 사용자·동일 초에도 access/refresh token이 서로 다른 값이며, 로그아웃 직후 재로그인에서 폐기된 refresh token 값이 재발급되지 않는다.
2. **최초 저장 동시성**: `UserRepository.findByIdForUpdate()`로 실제 사용자 행에 쓰기 잠금을 건 뒤 토큰을 조회·저장한다. 아직 refresh token 행이 없어도 잠금이 적용된다. 가입과 로그인에서 호출하는 `issueAndStore()`가 같은 규칙을 따른다.
3. **발급·로그아웃 직렬화**: `revoke()`도 같은 사용자 잠금을 잡는다. DB 변경은 트랜잭션 안에서 처리하며 사용자 행 → refresh token 순서를 유지한다. 없는 사용자나 삭제된 사용자는 401로 거부한다.
4. **기존 실패 테스트 수정**: `JwtAuthenticationTest.unknownPath()`에 유효한 access token을 추가했다. 404 응답 형식 검증을 유지하며 `/auth/**`를 다시 공개하지 않았다.
5. **테스트 정리 범위 추가**: `TestFixtures.reset()`에서 refresh token 행도 정리해 테스트 간 상태가 남지 않도록 했다.
6. **로그아웃 설명 보완**: Swagger에 access token은 만료까지 유효하며 클라이언트에서도 두 토큰을 삭제해야 한다고 명시했다.

## 유지한 정책

- 사용자당 refresh token은 1개다. 재로그인하면 가장 최근에 저장한 토큰만 사용할 수 있다.
- 로그아웃은 `POST /api/v1/auth/logout`, 유효한 access token이 필요하다.
- 해당 사용자의 refresh token이 없어도 200으로 응답한다. 반복 로그아웃이 가능하다.
- 원문 refresh token 대신 SHA-256 해시만 저장한다.
- Access token은 로그아웃 이후에도 만료까지 유효하다. 즉시 차단이나 기기별 세션은 이번에 추가하지 않았다.
- 사용자 단위 로그아웃이므로 이전 기기의 유효한 access token으로 로그아웃해도 현재 저장된 refresh token이 삭제된다. 현재 기기만 로그아웃하는 기능과는 다르다.
- 동시에 로그인한 요청은 모두 성공할 수 있지만, 사용자당 1개 정책에 따라 마지막 저장 토큰만 재발급에 사용할 수 있다.

## 검증

재발급 API 구현 전에 `./gradlew test --offline`으로 **486개 테스트 통과, 실패 0개, 오류 0개, 건너뜀 0개**를 확인했다.

추가한 검증:

- 대기 없이 연속 발급한 토큰 50쌍의 고유성.
- 본인 토큰 삭제, 다른 사용자 토큰 보존, 반복 로그아웃 200.
- 인증 누락/잘못된 토큰/만료 access token/refresh·signup token으로 로그아웃 시 401.
- 로그아웃 이후 access token 유효성은 기존 정책대로 유지.
- 재로그인 시 최신 해시만 저장.
- 로그아웃 후 재발급한 토큰이 폐기된 값과 다름.
- 최초 로그인 6건을 동시에 처리해 모두 성공하고 DB에는 1행만 남음.

테스트 DB는 PostgreSQL Testcontainers이며 실제 소셜 계정/운영 DB/비밀키는 사용하지 않았다.

## 파일

- `JwtProvider.java`: jti 추가.
- `UserRepository.java`: 사용자 행 잠금 조회.
- `RefreshTokenService.java`: 발급·폐기에 동일 잠금 적용.
- `AuthController.java`: 로그아웃 정책 문서화.
- `JwtAuthenticationTest.java`: 인증 조건 반영.
- `JwtProviderTest.java`, `LogoutApiTest.java`: 고유성·로그아웃·동시성 회귀 테스트.
- `TestFixtures.java`: refresh token 정리.

기존 작업의 `V13__create_refresh_tokens.sql`을 유지했다. 추가 마이그레이션이나 기존 데이터 직접 변경은 하지 않았다.
재발급 API의 구현과 최종 테스트 결과는 `RE_TOKEN.md`에 별도로 기록한다.
