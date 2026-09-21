#!/usr/bin/env bash
# 이미지와 커밋을 VM 에 반영하고 기동을 확인한다. 배포와 롤백이 같은 코드를 쓴다 —
# 롤백은 "이전 IMAGE·TARGET_SHA 로 다시 배포"일 뿐이라 따로 만들면 검증되지 않은 경로가 생긴다.
#
# 필요한 환경변수: VM_NAME VM_ZONE GCP_PROJECT_ID DEPLOY_PATH IMAGE TARGET_SHA

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# 헬스 폴링 3초 × 60 = 180초. compose pull + Flyway + 기동(약 42초)이 다 들어가야 하고,
# 오탐 롤백은 중단 시간을 두 배로 만든다. 대신 컨테이너가 죽어 있으면 즉시 끊는다.
read -r -d '' SCRIPT <<REMOTE || true
set -euo pipefail

# 저장소가 없으면 clone 한다. public 저장소라 인증이 필요 없다.
if [ ! -d "$DEPLOY_PATH/.git" ]; then
  sudo git clone -q https://github.com/${GITHUB_REPOSITORY:-swyp-app-6-team-2/backend}.git "$DEPLOY_PATH"
fi

cd "$DEPLOY_PATH"
sudo git fetch -q origin main
sudo git reset -q --hard "$TARGET_SHA"

# .env.vm 의 APP_IMAGE 를 갱신한다. 두지 않으면 재부팅 한 번으로 옛 버전이 뜬다.
sudo sed -i "s|^APP_IMAGE=.*|APP_IMAGE=$IMAGE|" .env.vm

COMPOSE="sudo docker compose -p swyp-backend --env-file .env.vm -f docker-compose.vm.yml"
\$COMPOSE pull app
\$COMPOSE up -d

# Caddyfile 은 바인드 마운트라 파일만 바뀌면 컨테이너가 재생성되지 않는다.
sudo docker exec starpick-vm-caddy caddy reload --config /etc/caddy/Caddyfile --adapter caddyfile

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
