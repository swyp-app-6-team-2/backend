#!/usr/bin/env bash
# DB 덤프와 .env.vm 사본을 GCS 로 올린다.
#
#   ./backup.sh daily      정기 백업 (cron). DB + .env.vm
#   ./backup.sh predeploy  배포 직전 백업. DB 만
#
# predeploy 는 아직 아무도 호출하지 않는다. CD 워크플로가 배포 전에 부르고
# 실패하면 배포를 중단하도록 바꾸는 것은 별도 작업이다.
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
# 매번 새 이름으로 올린다. 같은 경로를 덮어쓰려면 GCS 가 storage.objects.delete 를
# 요구하는데, 침해된 VM 이 과거 백업을 지우지 못하도록 그 권한을 주지 않았다.
if [[ "$MODE" == "daily" && -n "$SECRETS_BUCKET" ]]; then
  EDEST="gs://${SECRETS_BUCKET}/env-vm/env.vm-${TS}"
  "$GCLOUD" storage cp "$ENV_FILE" "$EDEST" --quiet
  echo "backup: env $EDEST"
fi

# ── 마지막 성공 시각을 메트릭으로 남긴다 ─────────────────────
# node_exporter 의 textfile collector 가 읽어 Grafana 로 보낸다. 6단계의
# "백업 미생성" 알림이 이 값에 의존한다. 디렉터리가 없으면 만든다 —
# 없다고 건너뛰면 cron 백업이 조용히 실패해도 알 방법이 없어진다.
#
# 쓰는 중인 파일을 읽지 않도록 임시 이름으로 쓴 뒤 옮긴다.
# collector 는 .prom 으로 끝나는 파일만 읽는다.
MDIR=/var/lib/node_exporter/textfile_collector
mkdir -p "$MDIR"
TMPM="$MDIR/starpick_backup_${MODE}.prom.$$"
printf 'starpick_backup_last_success_timestamp_seconds{mode="%s"} %s\n' "$MODE" "$(date -u +%s)" > "$TMPM"
mv "$TMPM" "$MDIR/starpick_backup_${MODE}.prom"
