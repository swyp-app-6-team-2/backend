#!/usr/bin/env bash
# VM 에서 명령 한 덩어리를 실행한다. IAP 터널 + OS Login 이라 SSH 키를 두지 않는다.
#
#   VM_NAME=... VM_ZONE=... GCP_PROJECT_ID=... ./remote.sh '명령'

set -euo pipefail

exec gcloud compute ssh "$VM_NAME" \
  --project="$GCP_PROJECT_ID" \
  --zone="$VM_ZONE" \
  --tunnel-through-iap \
  --quiet \
  --command="$1"
