## 🔗 Related Issue (관련 이슈)

Closes #109

## 📝 Changes (변경 사항)

1. `user_custom_ingredient` 테이블 추가(`V27__create_user_custom_ingredient.sql`) — `user_id`, `name`, `created_at`. 이름 중복 방지 UNIQUE 제약은 명세대로 추가하지 않았고, 공백만 있는 이름을 막는 `ck_user_custom_ingredient_name_not_blank` CHECK만 둠
2. `POST /api/v1/users/me/ingredients/custom` 구현 — 기존 `UserController`/`UserService`에 추가 (신규 컨트롤러/서비스 없음)
   - `CustomIngredientCreateRequest` record의 compact canonical constructor에서 Bean Validation 이전에 트림 처리 → `@NotBlank`/`@Size(max=50)`가 트림된 값 기준으로 동작
   - 등록은 `UserLifecycleGuard.lockActive`로 사용자 행을 잠그고 탈퇴 중/탈퇴 여부를 확인한 뒤 저장 (기존 `RecipeService` 등과 동일한 패턴)
   - 이름 중복은 허용 — 동일 이름을 여러 번 등록해도 각각 새 행으로 저장
3. 마스터/커스텀 재료 공유 응답 DTO 확장 — `UserIngredientResponse`에 `ingredientType`, `customIngredientId` 필드 추가, `fromCustom(UserCustomIngredient)` 팩토리 추가. 기존 마스터 재료 등록/조회 응답도 같은 DTO라 `ingredientType: "MASTER"`, `customIngredientId: null`을 함께 반환하도록 자연히 변경됨
4. `GET /api/v1/users/me/ingredients` 조회에 커스텀 재료 통합
   - `UserService.getIngredients()`의 마스터 전용 early-return(`ids.isEmpty()`)을 제거해 커스텀 재료만 있는 사용자도 정상 조회되도록 수정
   - `searchQuery`는 마스터/커스텀 이름 모두에 적용
   - 정렬: 기존 마스터 정렬(카테고리 → 이름 → ID) 유지, 커스텀 재료는 뒤에 이름 → ID 순으로 배치
5. 재료 기반 추천에 커스텀 재료 이름 매칭 추가
   - `RecipeRepository.findIngredientBasedCandidates` EXISTS 서브쿼리에 `trim(ri.name) in :customNames` OR 조건 추가 (기존 EXISTS 구조를 유지해 중복 매칭으로 인한 후보 중복을 그대로 방지)
   - `RecipeService.recommend()`의 `INGREDIENT_BASED` 분기에서 마스터 ID 목록과 커스텀 이름 목록을 모두 조회하고, 둘 다 비어있을 때만 빈 후보로 처리
6. 회원탈퇴 연동 — `UserWithdrawalService.resume()`의 `"ingredients"` 단계에서 `user_custom_ingredient`도 함께 삭제 (재시도/자동 복구 시에도 같은 단계에서 처리되므로 별도 로직 불필요)
7. 신규 `IngredientType` enum(MASTER/CUSTOM), `UserCustomIngredient` 엔티티, `UserCustomIngredientRepository` 추가

## ✅ Test / Verification

- [x] 로컬에서 정상적으로 빌드되는 것을 확인했습니다. (`./gradlew test` 846/846 통과)
- [x] 변경한 기능이 정상적으로 동작하는 것을 확인했습니다.
  - `CustomIngredientApiTest`: 정상 등록 및 앞뒤 공백 제거(내부 공백 유지), 이름 누락·null·공백·50자 초과 거절(정확히 50자는 통과), 잘못된 JSON 형식 거절, 동일 이름 반복 등록 허용, 다른 사용자에게 노출되지 않음, 인증 필요 및 탈퇴 계정 거절
  - `UserIngredientsApiTest`: 마스터 재료 뒤에 커스텀 재료가 이름·ID 순으로 배치되는지, 커스텀 필드(`ingredientId`/`categoryCode`/`iconUrl`이 null)가 올바른지, `searchQuery`가 마스터/커스텀 이름 모두에 적용되는지, 마스터가 없어도 커스텀 재료만으로 조회되는지, 사용자별로 격리되는지 확인
  - `RecipeRecommendationApiTest`: 커스텀 재료만 보유한 사용자의 추천, 트림 후 완전 일치(부분 문자열 불일치 포함, `생루꼴라` vs `루꼴라` 불일치 확인), 마스터·커스텀 조건을 OR로 결합한 추천, 동일 커스텀 재료 이름이 레시피 내 여러 번 나와도 후보가 중복되지 않는지 확인, 기존 마스터 기반 추천 회귀 테스트 전부 통과
  - `UserWithdrawalApiTest`: 탈퇴 시 `user_custom_ingredient`가 함께 삭제되는지 테이블 카운트 확인 목록에 추가하고 확인
  - `EnumCheckConstraintTest`: 신규 CHECK 제약(`ck_user_custom_ingredient_name_not_blank`)을 enum 무관 제외 목록에 등록해 전체 CHECK 제약 커버리지 테스트가 계속 통과하는지 확인
- [x] `.hprof` 힙 덤프는 생성되지 않았고 커밋 대상에도 포함하지 않았습니다.

## ☑️ Checklist (체크리스트)

- [x] 팀 코드 컨벤션을 준수하였나요? (`UserLifecycleGuard.lockActive` 잠금 패턴, `{Domain}CleanupService`류 없이 기존 `UserWithdrawalService` 단계에 인라인 추가, EXISTS 서브쿼리로 후보 중복 방지 등 기존 관례를 그대로 따랐습니다.)
- [x] 빌드가 정상적으로 통과되었나요?
- [ ] 리뷰어가 이해하기 쉽게 커밋 메시지를 작성했나요? — 아직 커밋 전입니다. (커밋·푸시는 요청자가 직접 진행)

## 💬 Additional Context (추가 사항)

- 이름 중복 방지, 마스터 재료 자동 연결, 커스텀 재료 수정·개별 삭제 API는 기술문서(`docs/specs/custom-ingredients.md`)에 명시된 대로 이번 범위에서 제외했습니다.
- `(user_id, name)` UNIQUE 제약은 기술문서 제안대로 추가하지 않았습니다. 공백만 있는 이름은 DB CHECK로 방어하되, 이는 enum 값 집합과 무관해 `EnumCheckConstraintTest`의 제외 목록에 등록했습니다.
- 커스텀 재료 이름 매칭은 대소문자 구분 없이 마스터 검색(`searchQuery`)에 쓰이는 `toLowerCase` 필터와 별개로, 레시피 재료 매칭에서는 명세대로 트림 후 완전 일치(대소문자 구분 유지)만 적용했습니다.
