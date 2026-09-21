#!/usr/bin/env bash
# VM 을 운영 구성으로 맞춘다. dev 와 prod 가 같은 파일을 쓴다.
#
#   sudo deploy/bootstrap.sh [배포경로]
#
# 배포경로 기본값은 이 스크립트의 상위 디렉터리다. cron 에 절대경로를 박아야 해서
# 인자로 받는다 — dev 는 ~/starpick, prod 는 /opt/starpick 이다.
#
# "없으면 만든다" 가 아니라 "목표 상태와 다를 때만 쓴다". 손으로 바뀐 드리프트를
# 교정하는 것이 목적이라, 이미 맞는 항목은 건드리지 않고 `유지` 로만 보고한다.
# 그래서 이미 구성된 VM 에 실행하면 아무것도 바뀌지 않는 것이 정상이다.
#
# Docker 설치부터 swap·로그 상한·Alloy·백업 cron 까지가 범위다.
# compose up 은 하지 않는다. startup-script 로 등록됐을 때 .env.vm 의 낡은
# APP_IMAGE 로 구버전이 조용히 뜨는 경로를 없앤다.
#
# Grafana 자격증명은 환경변수로 받는다. 값이 없고 기존 설정도 없으면 중단한다.
#
#   GCLOUD_RW_API_KEY=... GCLOUD_HOSTED_METRICS_URL=... GCLOUD_HOSTED_METRICS_ID=... \
#   GCLOUD_HOSTED_LOGS_URL=... GCLOUD_HOSTED_LOGS_ID=... STARPICK_ENV=prod \
#   sudo -E deploy/bootstrap.sh /opt/starpick

set -euo pipefail

ALLOY_VERSION="1.19.2"
SWAP_SIZE="2G"
BACKUP_HOUR="19"          # UTC. KST 04:00
DOCKER_LOG_MAX_SIZE="10m"
DOCKER_LOG_MAX_FILE="3"
AR_HOST="asia-northeast3-docker.pkg.dev"
TEXTFILE_DIR="/var/lib/node_exporter/textfile_collector"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEPLOY_PATH="${1:-$(cd "$SCRIPT_DIR/.." && pwd)}"
ENV_FILE="$DEPLOY_PATH/.env.vm"

CHANGED=()
KEPT=()

die()     { echo "bootstrap: $*" >&2; exit 1; }
changed() { CHANGED+=("$1"); echo "  [변경] $1"; }
kept()    { KEPT+=("$1");    echo "  [유지] $1"; }

# 내용이 다를 때만 쓴다. 쓰면 0, 이미 같으면 1 을 돌려준다.
write_if_diff() {
  local path="$1" mode="$2" content="$3"
  if [[ -f "$path" ]] && [[ "$(cat "$path")" == "$content" ]]; then
    return 1
  fi
  printf '%s\n' "$content" > "$path"
  chmod "$mode" "$path"
  return 0
}

# ── 0. 전제 ────────────────────────────────────────────────────────────────

[[ $EUID -eq 0 ]] || die "root 로 실행한다 — sudo $0 $*"
command -v python3 >/dev/null || die "python3 가 없다 (daemon.json 병합에 쓴다)"
[[ -f "$ENV_FILE" ]] || die ".env.vm 이 없다 — $ENV_FILE (사람이 먼저 배치한다)"

echo "bootstrap: 배포경로 $DEPLOY_PATH"

# ── 1. 환경변수 검사 ───────────────────────────────────────────────────────
#
# 빈 JWT_SECRET·ADMIN_PASSWORD 로 앱이 조용히 뜨는 것을 여기서 막는다.

echo "[1/6] 환경변수"
"$SCRIPT_DIR/check-env.sh" "$ENV_FILE" || die "필수 환경변수가 비어 있다"
kept ".env.vm 필수 키"

# ── 2. swap ────────────────────────────────────────────────────────────────
#
# swap 이 없으면 메모리가 모자랄 때 커널이 postgres 를 먼저 죽일 수 있다.

echo "[2/6] swap"
if swapon --show=NAME --noheadings 2>/dev/null | grep -qx /swapfile; then
  kept "swap $SWAP_SIZE"
else
  fallocate -l "$SWAP_SIZE" /swapfile || dd if=/dev/zero of=/swapfile bs=1M count=2048
  chmod 600 /swapfile
  mkswap /swapfile >/dev/null
  swapon /swapfile
  changed "swap $SWAP_SIZE 생성"
fi

# fstab 은 swapon 과 별개다. 빠져 있으면 재부팅 후 swap 이 사라진다.
if grep -qE '^/swapfile[[:space:]]' /etc/fstab; then
  kept "fstab swap 항목"
else
  echo '/swapfile none swap sw 0 0' >> /etc/fstab
  changed "fstab swap 항목 추가"
fi

if write_if_diff /etc/sysctl.d/99-starpick.conf 644 "vm.swappiness=10"; then
  sysctl --system >/dev/null
  changed "vm.swappiness=10"
else
  kept "vm.swappiness=10"
fi

# ── 3. Docker (설치 + 로그 상한) ───────────────────────────────────────────
#
# 로그 기본값은 무제한이라 컨테이너 로그가 디스크를 채운다.
# 사람이 넣었을 수 있는 다른 키를 지우지 않도록 병합한다.

echo "[3/6] Docker"

# 버전을 고정하지 않는다. 보안 업데이트를 받아야 하고, dev 도 저장소 방식이라
# 여기만 고정하면 두 환경이 갈라진다. Alloy 를 고정한 것과 기준이 다른 이유다.
if command -v docker >/dev/null && docker compose version >/dev/null 2>&1; then
  kept "docker $(docker --version | sed -E 's/Docker version ([^,]+).*/\1/')"
else
  install -m 0755 -d /etc/apt/keyrings
  if [[ ! -f /etc/apt/keyrings/docker.gpg ]]; then
    curl -fsSL https://download.docker.com/linux/ubuntu/gpg \
      | gpg --dearmor -o /etc/apt/keyrings/docker.gpg
    chmod a+r /etc/apt/keyrings/docker.gpg
  fi
  # shellcheck source=/dev/null
  CODENAME="$(. /etc/os-release && echo "$VERSION_CODENAME")"
  write_if_diff /etc/apt/sources.list.d/docker.list 644 \
    "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu $CODENAME stable" || true
  apt-get update -qq
  DEBIAN_FRONTEND=noninteractive apt-get install -y -qq \
    docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin >/dev/null
  systemctl enable --now docker >/dev/null 2>&1 || true
  changed "docker 설치"
fi

DAEMON_JSON=/etc/docker/daemon.json
mkdir -p /etc/docker
MERGED="$(
  MAX_SIZE="$DOCKER_LOG_MAX_SIZE" MAX_FILE="$DOCKER_LOG_MAX_FILE" \
  python3 - "$DAEMON_JSON" <<'PY'
import json, os, sys
path = sys.argv[1]
try:
    with open(path) as f:
        cfg = json.load(f)
except FileNotFoundError:
    cfg = {}
except json.JSONDecodeError:
    sys.exit("bootstrap: daemon.json 을 읽을 수 없다. 사람이 먼저 고친다")
cfg["log-driver"] = "json-file"
opts = cfg.setdefault("log-opts", {})
opts["max-size"] = os.environ["MAX_SIZE"]
opts["max-file"] = os.environ["MAX_FILE"]
print(json.dumps(cfg, indent=2, ensure_ascii=False))
PY
)"

if write_if_diff "$DAEMON_JSON" 644 "$MERGED"; then
  systemctl restart docker
  changed "daemon.json (docker 재시작함)"
  echo "  ! 로그 옵션은 컨테이너 생성 시점에 고정된다. 기존 컨테이너는 다음 배포부터 적용된다" >&2
else
  kept "daemon.json"
fi

# Artifact Registry 에서 앱 이미지를 받으려면 자격증명 헬퍼가 필요하다.
# gcloud auth configure-docker 가 하는 일과 같고, 파일 한 줄이라 직접 쓴다.
mkdir -p /root/.docker
if write_if_diff /root/.docker/config.json 600 \
  "{\"credHelpers\": {\"$AR_HOST\": \"gcloud\"}}"; then
  changed "AR 자격증명 헬퍼"
else
  kept "AR 자격증명 헬퍼"
fi

# ── 4. Alloy ───────────────────────────────────────────────────────────────
#
# 컨테이너가 아니라 호스트에 둔다. 배포·롤백 중에도 로그가 끊기면 안 되고,
# 알림을 거는 메모리·디스크가 호스트 지표라 컨테이너에서 보기에 맞지 않는다.

echo "[4/6] Alloy"
ALLOY_TOUCHED=0

# backup.sh 가 마지막 성공 시각을 여기에 쓰고 config.alloy 의 textfile collector 가 읽는다.
# 디렉터리가 없으면 수집이 실패해 알림이 뜬다(2026-09-21 prod 실측).
if [[ -d "$TEXTFILE_DIR" ]]; then
  kept "textfile collector 디렉터리"
else
  mkdir -p "$TEXTFILE_DIR"
  changed "textfile collector 디렉터리 생성"
fi

if [[ "$(dpkg-query -W -f='${Version}' alloy 2>/dev/null || true)" == "${ALLOY_VERSION}-1" ]]; then
  kept "alloy $ALLOY_VERSION"
else
  DEB="$(mktemp /tmp/alloy-XXXXXX.deb)"
  curl -fsSL -o "$DEB" \
    "https://github.com/grafana/alloy/releases/download/v${ALLOY_VERSION}/alloy-${ALLOY_VERSION}-1.amd64.deb"
  apt-get install -y "$DEB" >/dev/null
  rm -f "$DEB"
  changed "alloy $ALLOY_VERSION 설치"
  ALLOY_TOUCHED=1
fi

# discovery.docker 가 socket 을 읽어 컨테이너 이름을 라벨로 붙인다.
if id -nG alloy 2>/dev/null | tr ' ' '\n' | grep -qx docker; then
  kept "alloy 의 docker 그룹"
else
  usermod -aG docker alloy
  changed "alloy 를 docker 그룹에 추가"
  ALLOY_TOUCHED=1
fi

# 설정은 저장소가 원본이다. 손으로 고쳐 놨더라도 되돌린다(직전 파일은 남긴다).
if [[ -f /etc/alloy/config.alloy ]] \
   && cmp -s "$SCRIPT_DIR/alloy/config.alloy" /etc/alloy/config.alloy; then
  kept "config.alloy"
else
  [[ -f /etc/alloy/config.alloy ]] \
    && cp -a /etc/alloy/config.alloy "/etc/alloy/config.alloy.bak-$(date +%Y%m%d-%H%M%S)"
  install -m 644 -o root -g root "$SCRIPT_DIR/alloy/config.alloy" /etc/alloy/config.alloy
  changed "config.alloy 갱신"
  ALLOY_TOUCHED=1
fi

# 자격증명은 저장소가 아니라 systemd drop-in 에 둔다. 저장소가 public 이고,
# 그래야 dev·prod 가 같은 config.alloy 를 쓴다.
DROPIN_DIR=/etc/systemd/system/alloy.service.d
DROPIN="$DROPIN_DIR/env.conf"
if [[ -n "${GCLOUD_RW_API_KEY:-}" ]]; then
  for v in GCLOUD_HOSTED_METRICS_URL GCLOUD_HOSTED_METRICS_ID \
           GCLOUD_HOSTED_LOGS_URL GCLOUD_HOSTED_LOGS_ID STARPICK_ENV; do
    [[ -n "${!v:-}" ]] || die "$v 가 비어 있다. Grafana 값은 전부 함께 준다"
  done
  mkdir -p "$DROPIN_DIR"
  DROPIN_BODY="[Service]
Environment=\"GCLOUD_RW_API_KEY=$GCLOUD_RW_API_KEY\"
Environment=\"GCLOUD_HOSTED_METRICS_URL=$GCLOUD_HOSTED_METRICS_URL\"
Environment=\"GCLOUD_HOSTED_METRICS_ID=$GCLOUD_HOSTED_METRICS_ID\"
Environment=\"GCLOUD_HOSTED_LOGS_URL=$GCLOUD_HOSTED_LOGS_URL\"
Environment=\"GCLOUD_HOSTED_LOGS_ID=$GCLOUD_HOSTED_LOGS_ID\"
Environment=\"STARPICK_ENV=$STARPICK_ENV\""
  if write_if_diff "$DROPIN" 600 "$DROPIN_BODY"; then
    systemctl daemon-reload
    changed "alloy 자격증명"
    ALLOY_TOUCHED=1
  else
    kept "alloy 자격증명"
  fi
elif [[ -f "$DROPIN" ]]; then
  kept "alloy 자격증명 (기존 값 유지)"
else
  die "Grafana 자격증명이 없다. GCLOUD_RW_API_KEY 등을 주고 다시 실행한다"
fi

systemctl enable alloy >/dev/null 2>&1 || true
if [[ $ALLOY_TOUCHED -eq 1 ]]; then
  systemctl restart alloy
  changed "alloy 재시작"
elif ! systemctl is-active --quiet alloy; then
  systemctl start alloy
  changed "alloy 기동 (멈춰 있었다)"
else
  kept "alloy 실행 상태"
fi

# ── 5. 백업 cron ───────────────────────────────────────────────────────────
#
# cron 의 기본 PATH 에는 /snap/bin 이 없다. backup.sh 가 gcloud 를 절대경로로 부른다.

echo "[5/6] 백업 cron"
CRON_CMD="$DEPLOY_PATH/deploy/backup.sh daily >> /var/log/starpick-backup.log 2>&1"
CRON_LINE="0 $BACKUP_HOUR * * * $CRON_CMD"
CURRENT_CRON="$(crontab -l 2>/dev/null || true)"

if grep -Fxq "$CRON_LINE" <<<"$CURRENT_CRON"; then
  kept "백업 cron"
else
  # backup.sh 를 부르는 줄만 교체한다. 다른 작업은 건드리지 않는다.
  NEW_CRON="$(grep -vF 'deploy/backup.sh daily' <<<"$CURRENT_CRON" || true)"
  printf '%s\n%s\n%s\n' \
    "$NEW_CRON" \
    "# starpick 정기 백업 (bootstrap.sh 관리)" \
    "$CRON_LINE" \
    | grep -v '^$' | crontab -
  changed "백업 cron 등록"
fi

# ── 6. 요약 ────────────────────────────────────────────────────────────────

echo "[6/6] 요약"
echo "  변경 ${#CHANGED[@]}건 / 유지 ${#KEPT[@]}건"
if [[ ${#CHANGED[@]} -eq 0 ]]; then
  echo "  이미 목표 구성이다."
fi
echo
echo "compose up 은 하지 않는다. 앱 기동은 배포 워크플로가 한다."
