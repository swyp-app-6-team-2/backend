기술문서 — 커스텀 재료 추가 

## 기능 범위
- 사용자가 재료명을 입력하면 본인의 보유 재료에 즉시 추가한다.
- 다른 사용자의 목록이나 공통 재료 마스터에는 노출하지 않는다.
- 기존 마스터·본인의 커스텀 재료와 이름이 같아도 새 항목으로 저장한다.
- 재료 기반 추천에서 레시피 재료의 이름과 매칭한다.
- 회원탈퇴 시 해당 사용자의 커스텀 재료도 삭제한다.
- 중복 방지, 자동 마스터 연결, 수정·개별 삭제 API는 이번 범위에서 제외한다. 

## 등록 API
|항목|명세|
|Method|POST|
|Path|/api/v1/users/me/ingredients/custom|
|인증|Authorization: Bearer {accessToken}|
|Content-Type|application/json|

###요청
```
{
"name": "루꼴라"
}
```
|필드|타입|필수|설명|
|name|String|O|직접 입력한 재료명|

입력 검증은 다음과 같이 제안합니다.
- 앞뒤 공백을 제거한 뒤 저장한다.
- 누락·null·빈 문자열·공백만 입력한 경우 거절한다.
- 길이는 공백 제거 후 1~50자로 제한한다.
- 내부 띄어쓰기는 유지한다.
- 쉼표로 구분한 여러 재료를 자동 분리하지 않는다. 요청 하나가 재료 하나다.
### 200 OK
```
  {
  "status": 200,
  "message": "재료가 등록되었습니다.",
  "data": {
  "ingredientType": "CUSTOM",
  "ingredientId": null,
  "customIngredientId": 17,
  "name": "루꼴라",
  "categoryCode": null,
  "iconUrl": null
  }
}
 ```
  카테고리와 아이콘은 입력받지 않으므로 null로 반환한다. 프런트는 커스텀 재료를 직접 추가한 재료 영역에 표시하고 기본 아이콘을 사용한다. 
### 400 — 이름 검증 실패
  기존 공통 Bean Validation 응답 형식을 사용한다.
```
  {
  "status": 400,
  "message": "요청값이 올바르지 않습니다.",
  "data": {
  "code": "REQUEST_VALIDATION_FAILED",
  "errors": [
  {
  "field": "name",
  "reason": "재료명을 입력해주세요."
  }
 ]
}
}
  ```
  토큰이 없거나 유효하지 않은 경우, 탈퇴 중이거나 삭제된 사용자인 경우에는 기존 인증 정책에 따라 401을 반환한다.
## 저장 구조
   기존 user_ingredient는 재료 마스터 ID와 사용자를 연결하고 중복 등록을 방지한다. 커스텀 재료는 중복을 허용하므로 별도 테이블로 관리한다. 
### 신규 테이블 제안: user_custom_ingredient
|컬럼|타입|제약·설명|
|id|BIGINT|PK, 자동 생성|
|user_id|BIGINT|NOT NULL, users.user_id FK|
|name|VARCHAR(50)|NOT NULL, 공백만으로 구성된 값 금지|
|created_at|TIMESTAMPTZ|NOT NULL, 생성 시각|

- 사용자별 조회를 위한 user_id 인덱스를 추가한다.
- (user_id, name) UNIQUE 제약은 추가하지 않는다.
- ingredient 마스터와 기존 user_ingredient에는 행을 추가하지 않는다.
- 등록 트랜잭션은 사용자 행을 잠그고 활성 상태를 확인한 뒤 저장한다. 탈퇴와 동시에 요청되더라도 정리 이후 데이터가 다시 생성되지 않도록 한다.

## 내 재료 목록 조회 연동
   기존 GET /api/v1/users/me/ingredients에 커스텀 재료를 함께 반환한다.
   현재 구현의 data.ingredients 배열 구조를 유지하고, 항목에 ingredientType, customIngredientId를 추가한다.
```
   {
   "status": 200,
   "message": "내 재료 목록을 조회했습니다.",
   "data": {
   "ingredients": [
   {
   "ingredientType": "MASTER",
   "ingredientId": 3,
   "customIngredientId": null,
   "name": "닭가슴살",
   "categoryCode": "MEAT",
   "iconUrl": "https://example.com/images/ingredients/chicken.webp"
   },
   {
   "ingredientType": "CUSTOM",
   "ingredientId": null,
   "customIngredientId": 17,
   "name": "루꼴라",
   "categoryCode": null,
   "iconUrl": null
   }
   ]
   }
   }
   ```
- searchQuery는 기존 재료와 커스텀 재료 이름 모두에 적용한다.
- 기존 재료가 없어도 커스텀 재료가 있으면 반환한다.
- 같은 이름의 커스텀 재료 여러 건을 합치지 않고 각각 반환한다.
- 기존 재료의 정렬을 유지하고, 커스텀 재료는 뒤에 이름순·ID순으로 배치한다.
- 프런트 목록 식별자는 이름 대신 MASTER:{ingredientId} 또는 CUSTOM:{customIngredientId}를 사용한다.
  기존에는 ingredientId와 categoryCode가 있는 항목만 반환했으므로, 프런트에서 CUSTOM 분기와 null 처리를 함께 반영해야 한다. 기존 마스터 재료 추가 응답도 공유 DTO를 사용하므로 같은 필드를 일관되게 반환한다. 

## 재료 기반 추천 연동
   현재 추천은 보유한 마스터 재료 ID로 매칭한다. 여기에 커스텀 재료 이름 매칭을 추가한다.
   후보 조건은 다음과 같다.
```
   본인이 저장한 레시피
   AND 직전 추천 레시피 제외
   AND (
   기존 보유 마스터 재료 ID가 하나 이상 일치
   OR 본인의 커스텀 재료 이름이 하나 이상 일치
   )
   ```
   이름 매칭은 양쪽 앞뒤 공백을 제거한 후 완전 일치로 제안한다. 

|커스텀 재료|레시피 재료|매칭|
|루꼴라|루꼴라|O|
|루꼴라|앞뒤 공백이 있는 루꼴라|O|
|루꼴라|생루꼴라|X|
|대파|파|X|

- 비교 대상은 recipe_ingredient.name이다.
- 레시피 재료의 ingredientId가 없어도 이름이 같으면 매칭한다.
- 동의어·부분 일치·내부 공백 제거는 적용하지 않는다.
- 중복 재료나 여러 일치 항목 때문에 같은 레시피의 추천 확률이 높아지지 않도록 EXISTS로 후보를 판별한다.
- 커스텀 재료만 보유한 사용자도 추천받을 수 있어야 한다.
- 완전 랜덤 추천과 후보 없음 응답은 기존 동작을 유지한다.

## 회원탈퇴 연동
   UserWithdrawalService의 보유 재료 정리 단계에서 user_custom_ingredient도 삭제한다.
- 해당 사용자 데이터만 삭제한다.
- 이미 삭제된 경우에도 정상 처리한다.
- 중간 실패 후 재시도·자동 복구에서도 같은 정리 로직을 사용한다. 

## UI 연동
1. 입력이 비어 있거나 공백뿐이면 등록하기 버튼을 비활성화한다.
2. 입력 후 등록 버튼을 누르면 이름 하나를 전송한다.
3. 요청 중에는 버튼을 비활성화한다.
4. 성공하면 이전 재료관리 화면으로 돌아가 목록을 갱신한다.
5. 검증 오류는 입력창에 표시한다.
   중복을 허용하므로 동일 요청을 다시 보내면 새 항목이 추가된다. 응답을 받지 못한 경우에도 이미 저장됐을 수 있으므로 프런트에서 자동 재전송하지 않는다.
8. 구현 위치 및 테스트
   등록은 기존 UserController, UserService에 추가하고 요청·응답 DTO는 분리한다. 추천은 RecipeService와 추천 조회 쿼리를 확장한다.
   검증 항목:
- 정상 등록 및 앞뒤 공백 제거
- 누락·null·공백·길이 초과 입력 거절
- 동일 이름 반복 등록 허용
- 다른 사용자에게 조회·추천되지 않음
- 기존 재료와 커스텀 재료 통합 조회·검색
- 커스텀 재료만 보유한 경우의 추천
- 이름 완전 일치 및 마스터 ID 없는 레시피 재료 매칭
- 중복 재료로 레시피 후보가 중복되지 않음
- 회원탈퇴 삭제·재시도·등록 경쟁 처리
- 기존 마스터 등록 및 추천 기능 회귀 테스트