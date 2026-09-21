#!/usr/bin/env bash
# 이미지와 커밋을 VM 에 반영하고 기동을 확인한다. 배포와 롤백이 같은 코드를 쓴다 —
# 롤백은 "이전 IMAGE·TARGET_SHA 로 다시 배포"일 뿐이라 따로 만들면 검증되지 않은 경로가 생긴다.
#
# 전제: VM 에 저장소와 .env.vm 이 있고 bootstrap.sh 가 한 번 돌아 있어야 한다.
# 첫 VM 은 사람이 clone + .env.vm 배치 + bootstrap 을 하고, 그 뒤부터 이 스크립트가 맡는다.
#
# 필요한 환경변수: VM_NAME VM_ZONE GCP_PROJECT_ID DEPLOY_PATH IMAGE TARGET_SHA

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

: "${DEPLOY_PATH:?DEPLOY_PATH 가 비어 있다}"
: "${IMAGE:?IMAGE 가 비어 있다}"
: "${TARGET_SHA:?TARGET_SHA 가 비어 있다}"

# 헬스 폴링 3초 × 60 = 180초. compose pull + Flyway + 기동(약 42초)이 다 들어가야 하고,
# 오탐 롤백은 중단 시간을 두 배로 만든다. 대신 컨테이너가 죽어 있으면 즉시 끊는다.
read -r -d '' SCRIPT <<REMOTE || true
set -euo pipefail

cd "$DEPLOY_PATH"
sudo git fetch -q origin main
sudo git reset -q --hard "$TARGET_SHA"

# .env.vm 의 APP_IMAGE 를 갱신한다. 두지 않으면 재부팅 한 번으로 옛 버전이 뜬다.
# 치환이 0건이면 조용히 옛 이미지로 배포되므로 건수를 확인한다.
if ! sudo grep -qE '^APP_IMAGE=' .env.vm; then
  echo ".env.vm 에 APP_IMAGE 줄이 없습니다"
  exit 1
fi
sudo sed -i "s|^APP_IMAGE=.*|APP_IMAGE=$IMAGE|" .env.vm

COMPOSE="sudo docker compose -p swyp-backend --env-file .env.vm -f docker-compose.vm.yml"
\$COMPOSE pull app
\$COMPOSE up -d

# 정말 그 이미지로 떴는지 본다. 여기가 없으면 no-op 배포가 "성공"으로 보고된다.
RUNNING="\$(sudo docker inspect -f '{{.Config.Image}}' starpick-vm-app)"
if [ "\$RUNNING" != "$IMAGE" ]; then
  echo "기대한 이미지가 아닙니다 — 실행 중: \$RUNNING"
  exit 1
fi

# Caddyfile 은 바인드 마운트라 파일만 바뀌면 컨테이너가 재생성되지 않는다.
# 다만 caddy 가 방금 재생성됐으면 admin API 가 아직 안 떠 있어 몇 초 기다린다.
for i in 1 2 3 4 5; do
  if sudo docker exec starpick-vm-caddy caddy reload --config /etc/caddy/Caddyfile --adapter caddyfile; then
    break
  fi
  [ "\$i" = 5 ] && { echo "caddy reload 실패"; exit 1; }
  sleep 2
done

for i in \$(seq 1 60); do
  STATE="\$(sudo docker inspect -f '{{.State.Status}}' starpick-vm-app 2>/dev/null || echo missing)"
  if [ "\$STATE" = exited ] || [ "\$STATE" = restarting ] || [ "\$STATE" = missing ]; then
    echo "앱 컨테이너 상태가 \$STATE 입니다"
    sudo docker logs --tail 50 starpick-vm-app || true
    exit 1
  fi
  if curl -sf --max-time 3 http://127.0.0.1:8081/actuator/health >/dev/null; then
    echo "헬스 확인 (\$((i * 3))초)"
    exit 0
  fi
  sleep 3
done

echo "180초 안에 헬스가 올라오지 않았습니다"
sudo docker logs --tail 50 starpick-vm-app || true
exit 1
REMOTE

"$HERE/remote.sh" "$SCRIPT"
