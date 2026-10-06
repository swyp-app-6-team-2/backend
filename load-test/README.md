# 부하테스트

[k6](https://k6.io)로 API에 같은 조건의 부하를 걸어 어떤 API가 먼저 느려지는지 비교합니다.
게스트 계정으로 토큰을 받으므로 **비밀값 없이 k6만 있으면 돌릴 수 있습니다.** 회차마다 폴더를 따로 둡니다.

```
load-test/
├── lib.js              # 공통: 게스트 생성·토큰 재발급·응답 검사
├── create-guests.js    # 게스트 10명 생성 → guests.json
├── cleanup.js          # guests.json 의 게스트 탈퇴(데이터·이미지 정리)
└── 2026-10-01-round1/  # 회차 폴더: 시드·시나리오·결과
```

## 준비

- k6 (`brew install k6`), jq
- 대상 서버 주소를 `BASE_URL`로 넘깁니다. 로컬은 `http://localhost:8080`, dev는 `https://dev-api.starpick.cloud`입니다.
- **prod에는 돌리지 않습니다.**
- 회차 폴더에 `seed.sql`이 있으면 DB 접근이 필요합니다. 저장 한도를 올리는 것처럼 API로 만들 수 없는 데이터를 넣습니다. 시나리오에 그런 데이터가 필요 없으면 이 단계를 건너뜁니다.

## 실행 순서

모든 명령은 저장소 루트에서 시작합니다. k6가 결과 파일을 **실행한 디렉터리 기준**으로 쓰기 때문에 `cd` 위치를 지켜야 합니다. 다른 곳에서 실행하면 토큰이 든 `guests.json`이 `.gitignore` 밖에 생깁니다.

### 1. 게스트 생성

```bash
cd load-test
export BASE_URL=http://localhost:8080
k6 run create-guests.js
```

`guests.json`에 게스트가 남아 있으면 실행을 거부합니다. 남은 계정을 지울 토큰을 잃지 않기 위해서입니다. 먼저 `cleanup.js`를 실행하세요.

### 2. 시드 (회차에 `seed.sql`이 있을 때)

```bash
cd load-test
IDS="{$(jq -r 'map(.userId) | join(",")' guests.json)}"

# 로컬
JOBS=$(docker exec -i -e IDS="$IDS" starpick-postgres \
  sh -c 'psql -q -At -F, -v ON_ERROR_STOP=1 -v ids="$IDS" -U "$POSTGRES_USER" -d "$POSTGRES_DB"' \
  < 2026-10-01-round1/seed.sql)

# dev (VM 접근 권한 필요)
JOBS=$(gcloud compute ssh starpick-dev --project=starpick-mvp --zone=asia-northeast3-a --tunnel-through-iap -- \
  "sudo docker exec -i -e IDS='$IDS' starpick-vm-postgres sh -c 'psql -q -At -F, -v ON_ERROR_STOP=1 -v ids=\"\$IDS\" -U \"\$POSTGRES_USER\" -d \"\$POSTGRES_DB\"'" \
  < 2026-10-01-round1/seed.sql)

# 시드가 돌려준 ingestionJobId 를 guests.json 에 합친다
echo "$JOBS"
jq --arg jobs "$JOBS" '
  ($jobs | split("\n") | map(select(length > 0) | split(",") | {(.[0]): (.[1] | tonumber)}) | add) as $m
  | map(. + {ingestionJobId: $m[(.userId | tostring)]})' guests.json > guests.tmp && mv guests.tmp guests.json
```

### 3. 측정

회차 폴더의 README를 따릅니다. 스모크(VU 1명이 모든 요청을 한 번씩)를 먼저 돌려 check가 모두 통과하는지 확인한 뒤 본 측정을 합니다.

### 4. 정리

```bash
cd load-test
IDS="{$(jq -r 'map(.userId) | join(",")' guests.json)}"   # 남은 행 확인용. cleanup 전에 저장해 둔다
k6 run cleanup.js
```

탈퇴에 실패한 게스트만 `guests.json`에 남습니다. 전부 성공하면 `[]`입니다. 이어서 DB에 남은 행이 없는지 확인합니다(로컬 예시, dev는 2단계처럼 `gcloud compute ssh`로 감쌉니다).

```bash
echo "select (select count(*) from users where user_id = any('$IDS'::bigint[])) as users,
             (select count(*) from recipe where user_id = any('$IDS'::bigint[])) as recipe,
             (select count(*) from ingestion_job where user_id = any('$IDS'::bigint[])) as ingestion_job,
             (select count(*) from upload_object where user_id = any('$IDS'::bigint[])) as upload_object" \
  | docker exec -i starpick-postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
```

네 값이 모두 0이어야 합니다.

## 새 회차·새 도메인 추가

1. `YYYY-MM-DD-<이름>/` 폴더를 만들고 `load.js`를 둡니다. `../guests.json`을 읽고 `../lib.js`를 가져다 씁니다.
2. 측정 조건과 결과는 그 폴더의 `README.md`에 적습니다.
3. 원본 결과(`results/`)와 `guests.json`은 커밋하지 않습니다(`.gitignore`).

## 주의

- 게스트 토큰을 화면이나 로그에 출력하지 않습니다.
- **dev가 최신 코드인지 먼저 확인합니다.** dev VM이 꺼져 있는 동안 머지된 커밋은 자동 배포가 실패해 반영되지 않습니다(이미지 빌드는 성공). VM을 켠 뒤 Actions의 `CD - Dev` 최신 실행에서 실패한 job을 재실행하고, 구동 중인 이미지 태그가 main 최신 SHA인지 확인합니다.
- **배포 직후에는 몇 분 기다렸다가 잽니다.** 기동 직후에는 JIT 컴파일로 CPU가 포화돼 값이 크게 튑니다(2회차에서 기동 2분 후 측정이 오염되어 다시 쟀습니다).
- dev에서 측정하는 동안 main에 머지하면 자동 배포로 dev 앱이 재시작되어 측정이 무효가 됩니다. 시작 전에 팀 채널에 알립니다.
- 업로드는 실제 GCS 버킷에 올라갑니다. 끝나면 반드시 `cleanup.js`로 정리합니다.
