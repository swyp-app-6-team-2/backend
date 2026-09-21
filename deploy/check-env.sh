#!/usr/bin/env bash
# .env.vm 의 필수 키가 없거나 비어 있으면 종료 코드 1 로 멈춘다.
#
# compose 의 environment 블록은 파일에 없는 변수를 빈 문자열로 채워 주입하므로,
# application.yml 의 기본값 없는 ${VAR} 만으로는 누락이 잡히지 않는다.
# 빈 JWT_SECRET·ADMIN_PASSWORD 로 앱이 조용히 뜨는 것을 막는 것이 이 스크립트의 목적이다.
#
# 사용: ./check-env.sh [.env.vm 경로]

set -uo pipefail

F="${1:-.env.vm}"

# application.yml 에서 기본값 없는 ${VAR} + compose 가 요구하는 값
#
# API_DOMAIN 이 비면 Caddy 기동이 실패해 80·443 이 통째로 죽는다(2026-09-19 실측).
REQUIRED=(
  API_DOMAIN
  POSTGRES_DB
  POSTGRES_USER
  POSTGRES_PASSWORD
  APP_IMAGE
  JWT_SECRET
  GOOGLE_CLIENT_ID
  KAKAO_APP_ID
  NAVER_CLIENT_ID
  NAVER_CLIENT_SECRET
  APPLE_CLIENT_ID
  GCS_BUCKET
  INGREDIENT_ICON_BASE_URL
  BACKUP_BUCKET
  SECRETS_BUCKET
  ADMIN_USERNAME
  ADMIN_PASSWORD
  ADMOB_AD_UNIT_ANDROID
)

if [[ ! -f "$F" ]]; then
  echo "check-env: 파일이 없습니다 — $F" >&2
  exit 1
fi

missing=()
empty=()

for k in "${REQUIRED[@]}"; do
  line=$(grep -m1 "^${k}=" "$F" || true)
  if [[ -z "$line" ]]; then
    missing+=("$k")
  elif [[ -z "${line#*=}" ]]; then
    empty+=("$k")
  fi
done

if (( ${#missing[@]} == 0 && ${#empty[@]} == 0 )); then
  echo "check-env: 필수 ${#REQUIRED[@]}개 모두 확인 — $F"
  exit 0
fi

(( ${#missing[@]} > 0 )) && printf 'check-env: 키 없음  %s\n' "${missing[*]}" >&2
(( ${#empty[@]}   > 0 )) && printf 'check-env: 값 없음  %s\n' "${empty[*]}" >&2
exit 1
