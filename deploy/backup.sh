#!/usr/bin/env bash
# DB 덤프와 .env.vm 사본을 GCS 로 올린다.
#
#   ./backup.sh daily      정기 백업 (cron). DB + .env.vm
#   ./backup.sh predeploy  배포 직전 백업. DB 만
#
# cron 의 기본 PATH 에는 /snap/bin 이 없다. gcloud 를 절대경로로 부르는 이유다.
# 파이프 실패를 종료 코드로 전파하지 않으면 잘린 덤프가 정상 객체로 올라간다.

set -euo pipefail

MODE="${1:-daily}"
case "$MODE" in
  daily|predeploy) ;;
  *) echo "backup: 알 수 없는 모드 — $MODE (daily|predeploy)" >&2; exit 2 ;;
esac

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${ENV_FILE:-$DIR/.env.vm}"
GCLOUD="${GCLOUD:-/snap/bin/gcloud}"
PG_CONTAINER="${PG_CONTAINER:-starpick-vm-postgres}"

[[ -f "$ENV_FILE" ]] || { echo "backup: .env.vm 이 없습니다 — $ENV_FILE" >&2; exit 1; }
[[ -x "$GCLOUD"   ]] || { echo "backup: gcloud 를 찾을 수 없습니다 — $GCLOUD" >&2; exit 1; }

val() { grep -m1 "^${1}=" "$ENV_FILE" | cut -d= -f2- ; }

PGDB=$(val POSTGRES_DB)
PGUSER=$(val POSTGRES_USER)
BACKUP_BUCKET=$(val BACKUP_BUCKET)
SECRETS_BUCKET=$(val SECRETS_BUCKET)

for v in PGDB PGUSER BACKUP_BUCKET; do
  [[ -n "${!v}" ]] || { echo "backup: $v 값이 비었습니다" >&2; exit 1; }
done

TS=$(date -u +%Y%m%d-%H%M%S)
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT

# ── DB 덤프 ───────────────────────────────────────────────
DUMP="$TMP/${PGDB}-${TS}.dump"
docker exec "$PG_CONTAINER" pg_dump -U "$PGUSER" -d "$PGDB" -Fc > "$DUMP"
[[ -s "$DUMP" ]] || { echo "backup: 덤프가 비었습니다" >&2; exit 1; }

DEST="gs://${BACKUP_BUCKET}/${MODE}/${PGDB}-${TS}.dump"
"$GCLOUD" storage cp "$DUMP" "$DEST" --quiet

SIZE=$("$GCLOUD" storage ls -l "$DEST" | awk 'NR==1{print $1}')
[[ "${SIZE:-0}" -gt 0 ]] || { echo "backup: 업로드 객체 크기가 0 입니다 — $DEST" >&2; exit 1; }
echo "backup: DB  $DEST (${SIZE} bytes)"

# ── .env.vm 사본 (daily 만) ───────────────────────────────
if [[ "$MODE" == "daily" && -n "$SECRETS_BUCKET" ]]; then
  EDEST="gs://${SECRETS_BUCKET}/env-vm/env.vm"
  "$GCLOUD" storage cp "$ENV_FILE" "$EDEST" --quiet
  echo "backup: env $EDEST (버전 관리)"
fi

# ── 마지막 성공 시각을 메트릭으로 남긴다 (Alloy textfile collector) ──
MDIR=/var/lib/node_exporter/textfile_collector
if [[ -d "$MDIR" ]]; then
  printf 'starpick_backup_last_success_timestamp_seconds{mode="%s"} %s\n' "$MODE" "$(date -u +%s)" \
    > "$MDIR/starpick_backup.prom.$$" && mv "$MDIR/starpick_backup.prom.$$" "$MDIR/starpick_backup_${MODE}.prom"
fi
