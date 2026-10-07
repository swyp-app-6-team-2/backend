#!/usr/bin/env python3
"""prod 의 지난 24시간을 Grafana 에서 모아 Discord 로 보고한다.

    python3 daily-report.py            보고를 보낸다 (cron, 매일 00:00 UTC = 09:00 KST)
    python3 daily-report.py --dry-run  보내지 않고 출력만 한다

설정은 /etc/starpick/daily-report.env (GRAFANA_URL·GRAFANA_TOKEN·DISCORD_WEBHOOK_URL).
.env.vm 에 두지 않는다 — compose 가 그 파일을 통째로 앱 컨테이너에 넣는다.

prod VM 이 Ubuntu 22.04 라 Python 3.10 문법만 쓰고 표준 라이브러리만 쓴다.

로그와 Discord 에는 HTTP 상태 코드와 예외 이름만 남긴다. cron 로그 파일은 0644 라
Grafana 응답 본문·웹훅 URL·토큰이 새면 VM 의 누구나 읽는다.
"""

import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timedelta, timezone

KST = timezone(timedelta(hours=9))
WINDOW = 24 * 3600
STEP = 60
TIMEOUT = 30
MESSAGE_LIMIT = 1900  # Discord 는 2,000자. 글자 수 세는 방식 차이를 감안해 여유를 둔다

PROM = "grafanacloud-prom"
LOGS = "grafanacloud-logs"
ALERT_HISTORY = "grafanacloud-alert-state-history"

PROBE_INSTANCE = "https://api.starpick.cloud/actuator/health"
GITHUB_REPO = "swyp-app-6-team-2/backend"

# Discord 로 실제로 가는 규칙의 폴더. 이력의 대부분은 Grafana 가 자동으로 만든
# Asserts 규칙(alerting-tprwygwwerdz)이라 거르지 않으면 매일 ⚠️ 가 된다(2026-09-28 실측).
ALERT_FOLDERS = {
    "efyqcpuec4pvke",                    # 우리 규칙 (starpick 그룹)
    "grafana-synthetic-monitoring-app",  # 외부 접속 검사
    "integration---linux-node",          # VM 통합 규칙
}

# 2초 주기 poll 43,200회 · 매분 run 1,440회의 90%. 배포로 멈추는 1~2분을 감안한 값이다.
WORKER_MIN = {"poll": 38_880, "run": 1_296}
PROBE_MIN = 0.99
CERT_MIN_DAYS = 14  # Caddy 가 자동 갱신하지만 실패해도 아무도 모른다. 만료 2주 전부터 알린다
DISK_MAX = 0.70
MEMORY_MAX = 0.85
BACKUP_MAX_AGE = 26 * 3600

# 하루 요청이 수백 건이라 p95 는 느린 요청 한 건에 흔들린다(2026-10-07 배포 3분 뒤 0.83s 오판).
# 구간 평균을 보되 건수가 너무 적은 API 는 뺀다.
LATENCY_MIN_COUNT = 10
SERVER_URI_EXCLUDE = "UNKNOWN|REDIRECTION|/images/.*"

# 서비스 숫자(가입·로그인·레시피 저장 등)는 FE 보고와 겹쳐서 뺐다. 분석 줄의 "요청 N" 하나만 남긴다.
ANALYSIS_REQUEST_URI = "/api/v1/ingestion-jobs"
POSTGRES_ERROR_QUERY = '{env="prod",container="starpick-vm-postgres"} |~ "ERROR|FATAL|PANIC"'

APP_LOG_QUERY = (
    '{env="prod",container="starpick-vm-app"} |~ "'
    "분석을 완료했습니다|failureCode=|안전 차단으로|분석이 최종 실패|예상하지 못한 오류로 분석"
    "|Instagram 수집이 재시도 끝에 실패|stale Job 을 복구했습니다|대기 상한을 넘긴|식사 알림을 보냈습니다"
    '"'
)
APP_LOG_LIMIT = 5000

OK, WARN, INFO, PLAIN = "✅", "⚠️", "ℹ️", ""


class QueryError(Exception):
    """조회 하나가 실패했다. 그 줄만 '조회 실패' 로 표시하고 나머지는 보낸다."""


class AuthError(QueryError):
    """토큰이 틀렸다. 어떤 조회도 성공하지 못하므로 보고 전체를 포기한다."""


# ── 설정 ────────────────────────────────────────────────────────────────────


def load_config(path):
    cfg = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, value = line.split("=", 1)
            cfg[key.strip()] = value.strip()
    missing = [k for k in ("GRAFANA_URL", "GRAFANA_TOKEN", "DISCORD_WEBHOOK_URL") if not cfg.get(k)]
    if missing:
        raise ValueError("설정 값이 비었습니다: " + ", ".join(missing))
    cfg["GRAFANA_URL"] = cfg["GRAFANA_URL"].rstrip("/")
    return cfg


# ── Grafana ─────────────────────────────────────────────────────────────────


class Grafana:
    def __init__(self, url, token):
        self.url = url
        self.token = token

    def _get(self, uid, path, params):
        query = urllib.parse.urlencode(params)
        req = urllib.request.Request(
            f"{self.url}/api/datasources/proxy/uid/{uid}{path}?{query}",
            headers={"Authorization": f"Bearer {self.token}", "User-Agent": "starpick-daily-report"},
        )
        try:
            with urllib.request.urlopen(req, timeout=TIMEOUT) as res:
                return json.load(res)["data"]
        except urllib.error.HTTPError as e:
            if e.code in (401, 403):
                raise AuthError(f"Grafana 응답 {e.code}") from None
            raise QueryError(f"Grafana 응답 {e.code}") from None
        except (urllib.error.URLError, TimeoutError, ValueError, KeyError) as e:
            raise QueryError(type(e).__name__) from None

    def instant(self, promql, at):
        data = self._get(PROM, "/api/v1/query", {"query": promql, "time": at})
        return [(r["metric"], float(r["value"][1])) for r in data["result"]]

    def series(self, promql, start, end):
        data = self._get(PROM, "/api/v1/query_range",
                         {"query": promql, "start": start, "end": end, "step": STEP})
        return [(r["metric"], [(float(t), float(v)) for t, v in r["values"]]) for r in data["result"]]

    def logs(self, uid, logql, start, end, limit):
        data = self._get(uid, "/loki/api/v1/query_range", {
            "query": logql, "start": f"{int(start)}000000000", "end": f"{int(end)}000000000",
            "limit": limit, "direction": "forward",
        })
        return [(r["stream"], line) for r in data["result"] for _, line in r["values"]]

    def increase(self, selector, by, start, end):
        """카운터가 구간 안에서 늘어난 양을 라벨 조합별로 센다(소수 그대로).

        increase() 를 쓰지 않는다. Micrometer 는 (uri,status) 조합의 시리즈를 첫 요청이 올 때
        만들어서, 구간 안에서 새로 생긴 시리즈의 첫 값을 increase() 가 세지 못한다.
        처음 난 500 한 건이 '5xx 0건' 이 된다.
        """
        totals = {}
        for labels, samples in self.series(f"sum by ({','.join(by)}) ({selector})", start, end):
            total = samples[0][1] if samples[0][0] > start + 2 * STEP else 0.0
            for (_, prev), (_, cur) in zip(samples, samples[1:]):
                total += cur - prev if cur >= prev else cur  # 내려가면 재시작으로 인한 리셋
            key = tuple(labels.get(b, "") for b in by)
            totals[key] = totals.get(key, 0) + total
        return totals

    def counter_total(self, selector, by, start, end):
        """건수 카운터용. 반올림한 정수이고 0건인 조합은 뺀다."""
        totals = {k: round(v) for k, v in self.increase(selector, by, start, end).items()}
        return {k: v for k, v in totals.items() if v > 0}


# ── 표기 ────────────────────────────────────────────────────────────────────


def n(value):
    return f"{int(value):,}"


def kst(ts):
    return datetime.fromtimestamp(ts, KST).strftime("%m-%d %H:%M")


def short_uri(uri):
    return uri[len("/api/v1"):] if uri.startswith("/api/v1/") else uri


def top(items, limit):
    """(텍스트, 건수) 목록을 건수 내림차순으로 limit 개까지 잇고 나머지는 '외 N개' 로 줄인다."""
    items = sorted(items, key=lambda x: -x[1])
    shown = [text for text, _ in items[:limit]]
    if len(items) > limit:
        shown.append(f"외 {len(items) - limit}개")
    return ", ".join(shown)


# ── 상태 (판정 있음) ────────────────────────────────────────────────────────


def check_app(g, start, end):
    rows = g.instant('up{env="prod",job="starpick/app"}', end)
    if not rows or all(v == 0 for _, v in rows):
        return WARN, "앱 지표 없음 — 앱·Alloy 확인"
    return OK, "앱 지표 수집 중"


def check_probe(g, start, end):
    rows = g.instant(
        f'avg by (probe) (avg_over_time(probe_success{{instance="{PROBE_INSTANCE}"}}[24h]))', end)
    if not rows:
        return WARN, "외부 접속 지표 없음"
    rows.sort(key=lambda r: r[0].get("probe", ""))
    text = " · ".join(f"{m.get('probe', '?')} {v * 100:.2f}%" for m, v in rows)
    warn = any(v < PROBE_MIN for _, v in rows)

    cert = g.instant(f'min(probe_ssl_earliest_cert_expiry{{instance="{PROBE_INSTANCE}"}}) - time()', end)
    if cert:
        days = cert[0][1] / 86400
        text += f" · 인증서 {days:.0f}일 남음"
        warn = warn or days < CERT_MIN_DAYS
    else:
        text += " · 인증서 지표 없음"
    return (WARN if warn else OK), f"외부 접속 {text}"


def prod_deploys(start):
    """구간 안의 prod 배포 실행 기록. 저장소가 public 이라 토큰 없이 조회한다(IP 당 시간 60회)."""
    since = datetime.fromtimestamp(start, timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    req = urllib.request.Request(
        f"https://api.github.com/repos/{GITHUB_REPO}/actions/workflows/cd-prod.yml/runs"
        f"?created=%3E%3D{since}&per_page=20",
        headers={"Accept": "application/vnd.github+json", "User-Agent": "starpick-daily-report"},
    )
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT) as res:
            return json.load(res)["workflow_runs"]
    except urllib.error.HTTPError as e:
        raise QueryError(f"GitHub 응답 {e.code}") from None
    except (urllib.error.URLError, TimeoutError, ValueError, KeyError) as e:
        raise QueryError(type(e).__name__) from None


def check_deploy(g, start, end):
    """배포와 앱 재시작을 한 줄로 본다. 배포 없이 재시작했다면 크래시·OOM 을 의심한다."""
    sel = 'process_start_time_seconds{env="prod",job="starpick/app"}'
    restarts = int(sum(v for _, v in g.instant(f"changes({sel}[24h])", end)))
    current = g.instant(sel, end)
    runs = prod_deploys(start)

    ok = sum(1 for r in runs if r.get("conclusion") == "success")
    running = sum(1 for r in runs if r.get("status") != "completed")
    failed = len(runs) - ok - running  # failure·cancelled 등. 롤백된 배포도 실패로 끝난다

    def title(run):
        lines = [x for x in ((run.get("head_commit") or {}).get("message") or "").splitlines() if x.strip()]
        if not lines:
            return ""
        # 머지 커밋은 첫 줄이 "Merge pull request #N from 브랜치" 라 무엇인지 안 보인다. 본문의 PR 제목을 쓴다.
        m = re.match(r"Merge pull request (#\d+)", lines[0])
        if m and len(lines) > 1:
            return f"{lines[-1][:40]} ({m.group(1)})"
        return lines[0][:40]

    # head_sha 는 실행한 브랜치의 HEAD 다. SHA 를 직접 입력한 배포라면 실제 배포본과 다를 수 있다.
    commits = [(f"{r['head_sha'][:7]} {title(r)}", 0) for r in runs if r.get("conclusion") == "success"]

    if runs:
        text = f"배포 {len(runs)}회 (성공 {ok}"
        text += f" · 실패 {failed}" if failed else ""
        text += f" · 진행 중 {running}" if running else ""
        text += ")"
    else:
        text = "배포 없음"
    text += f" · 재시작 {restarts}회"
    if current:
        text += f" · 마지막 기동 {kst(max(v for _, v in current))}"
    if commits:
        text += " — " + top(commits, 3)

    unexplained = not runs and restarts > 0
    if unexplained:
        text += " — 배포 없이 재시작했습니다. 크래시·OOM 확인"
    return (WARN if failed or unexplained else OK), text


def check_errors(g, start, end):
    server = g.counter_total('http_server_requests_seconds_count{env="prod",status=~"5.."}',
                             ("method", "uri", "status"), start, end)
    logs = g.counter_total('logback_events_total{env="prod",level=~"error|warn"}', ("level",), start, end)
    s5xx = sum(server.values())
    errors = logs.get(("error",), 0)
    warns = logs.get(("warn",), 0)
    text = f"5xx {n(s5xx)}건 · ERROR {n(errors)}건 · WARN {n(warns)}건"

    # 앱 로그에 안 잡히는 DB 쪽 오류(연결 거부·교착·디스크)를 본다. 이 조회만 실패해도 나머지는 살린다.
    db_errors = 0
    try:
        db_errors = len(g.logs(LOGS, POSTGRES_ERROR_QUERY, start, end, 1000))
        text += f" · DB 오류 로그 {n(db_errors)}건"
    except AuthError:
        raise
    except QueryError as e:
        text += f" · DB 오류 로그 조회 실패 ({e})"

    if server:
        text += " — " + top([(f"{m} {short_uri(u)} {s} ×{c}", c) for (m, u, s), c in server.items()], 3)
    return (WARN if s5xx or errors or db_errors else OK), text


def check_worker(g, start, end):
    totals = g.counter_total(
        'tasks_scheduled_execution_seconds_count{env="prod",code_function=~"poll|run",'
        'code_namespace=~".*(IngestionSchedule|NotificationSchedule)"}',
        ("code_function",), start, end)
    # 시리즈가 아예 없으면 0 이다. GEMINI_API_KEY 가 빠져 Worker 가 등록되지 않은 경우가 그렇다.
    poll = totals.get(("poll",), 0)
    run = totals.get(("run",), 0)
    ok = poll >= WORKER_MIN["poll"] and run >= WORKER_MIN["run"]
    # 분석 폴링은 2초마다 도는 횟수다(분석 건수가 아니다). 건수는 서비스 섹션에 있다.
    label = "Worker 동작 중" if ok else "Worker 멈춤 의심"
    return (OK if ok else WARN), f"{label} (분석 폴링 {n(poll)} · 알림 실행 {n(run)})"


def check_backup(g, start, end):
    rows = g.instant('starpick_backup_last_success_timestamp_seconds{env="prod",mode="daily"}', end)
    if not rows:
        return WARN, "백업 기록 없음"
    last = max(v for _, v in rows)
    return (WARN if end - last > BACKUP_MAX_AGE else OK), f"백업 {kst(last)}"


def check_external(g, start, end):
    totals = g.counter_total('http_client_requests_seconds_count{env="prod",outcome!="SUCCESS"}',
                             ("client_name", "outcome", "status"), start, end)
    # 서버 오류·IO 오류만 우리 쪽 문제다. 만료된 카카오 토큰·없는 영상 같은 4xx 는 예상 가능한 결과다.
    errors = [(f"{c} {s} ×{v}", v) for (c, o, s), v in totals.items() if o in ("SERVER_ERROR", "UNKNOWN")]
    others = [(f"{c} {s} ×{v}", v) for (c, o, s), v in totals.items() if o not in ("SERVER_ERROR", "UNKNOWN")]
    err_count = sum(v for _, v in errors)
    text = f"외부 API 오류 {n(err_count)}건"
    if errors:
        text += " — " + top(errors, 3)
    if others:
        text += f" (4xx 등 {n(sum(v for _, v in others))}건: {top(others, 3)})"
    return (WARN if err_count else OK), text


def check_alerts(g, start, end):
    rows = g.logs(ALERT_HISTORY, '{from="state-history"} | json | current=~"Alerting.*"', start, end, 1000)
    names = sorted({
        s.get("ruleTitle", "?") for s, _ in rows
        if s.get("folderUID") in ALERT_FOLDERS and s.get("labels_env", "") in ("", "prod")
    })
    if not names:
        return OK, "울린 알림 없음"
    return WARN, "울린 알림: " + top([(x, 0) for x in names], 8)


def check_resources(g, start, end):
    """포화도. 메모리·스왑이 가장 빠듯하다(VM 4GB). OOM·DB 커넥션은 0이 아닐 때만 문구에 붙인다."""

    def value(promql):
        rows = g.instant(promql, end)
        return rows[0][1] if rows else None

    memory = value('max(max_over_time(instance:node_memory_utilisation:ratio{env="prod"}[24h]))')
    swap = value('sum(increase(node_vmstat_pswpin{env="prod"}[24h]) + increase(node_vmstat_pswpout{env="prod"}[24h]))')
    heap = value('max(max_over_time(jvm_memory_usage_after_gc{env="prod",area="heap"}[24h]))')
    disk = value('max(1 - node_filesystem_avail_bytes{env="prod",mountpoint="/"}'
                 ' / node_filesystem_size_bytes{env="prod",mountpoint="/"})')
    oom = value('sum(increase(node_vmstat_oom_kill{env="prod"}[24h]))') or 0
    db_pending = value('max(max_over_time(hikaricp_connections_pending{env="prod"}[24h]))') or 0
    db_timeout = value('sum(increase(hikaricp_connections_timeout_total{env="prod"}[24h]))') or 0

    if memory is None or disk is None:
        return WARN, "자원 지표 없음"

    parts = [f"메모리 VM {memory * 100:.0f}%"]
    if swap is not None:
        parts.append(f"스왑 {n(swap)}페이지")
    if heap is not None:
        parts.append(f"힙(GC 후) {heap * 100:.0f}%")
    parts.append(f"디스크 {disk * 100:.0f}%")
    if oom >= 1:
        parts.append(f"OOM kill {n(oom)}회")
    if db_pending >= 1 or db_timeout >= 1:
        parts.append(f"DB 커넥션 대기 {n(db_pending)} · 타임아웃 {n(db_timeout)}")

    warn = memory >= MEMORY_MAX or disk >= DISK_MAX or oom >= 1 or db_pending >= 1 or db_timeout >= 1
    return (WARN if warn else OK), " · ".join(parts)


# ── 참고 (판정 없음) ────────────────────────────────────────────────────────


def info_4xx(g, start, end):
    totals = g.counter_total('http_server_requests_seconds_count{env="prod",status=~"4.."}',
                             ("method", "uri", "status"), start, end)
    if not totals:
        return INFO, "4xx 없음"
    # uri=UNKNOWN 은 핸들러 매핑 전에 끝난 요청이다. 401 이면 인증 필터가 막은 것이라 메서드 구분 없이 합친다.
    auth = sum(c for (_, u, s), c in totals.items() if (u, s) == ("UNKNOWN", "401"))
    items = [(f"인증 실패 {n(auth)}", auth)] if auth else []
    items += [(f"{m} {short_uri(u)} {s} ×{n(c)}", c)
              for (m, u, s), c in totals.items() if (u, s) != ("UNKNOWN", "401")]
    return INFO, "4xx " + top(items, 5).replace(", ", " · ")


def info_traffic(g, start, end):
    totals = g.counter_total(
        f'http_server_requests_seconds_count{{env="prod",uri!~"{SERVER_URI_EXCLUDE}"}}', ("uri",), start, end)
    return INFO, f"요청 {n(sum(totals.values()))}건"


def info_latency(g, start, end):
    """API 별 구간 평균. p95 는 하루 수백 건에서는 요청 한 건이 그대로 값이 되어 쓰지 않는다."""
    selector = (f'http_server_requests_seconds_%s{{env="prod",status=~"2..",'
                f'uri!~"{SERVER_URI_EXCLUDE}"}}')
    by = ("method", "uri")
    counts = g.counter_total(selector % "count", by, start, end)
    seconds = g.increase(selector % "sum", by, start, end)
    rows = [(key, seconds.get(key, 0.0) / count, count)
            for key, count in counts.items() if count >= LATENCY_MIN_COUNT]
    if not rows:
        return INFO, f"느린 API 없음 (건수 {LATENCY_MIN_COUNT}건 이상인 API 기준)"
    rows.sort(key=lambda r: -r[1])
    text = " · ".join(f"{m} {short_uri(u)} {avg * 1000:.0f}ms·{n(count)}건" for (m, u), avg, count in rows[:3])
    return INFO, f"느린 API(평균) {text}"


# ── 서비스 숫자 ─────────────────────────────────────────────────────────────


def analysis_requests(g, start, end):
    totals = g.counter_total(
        f'http_server_requests_seconds_count{{env="prod",method="POST",uri="{ANALYSIS_REQUEST_URI}"}}',
        ("status",), start, end)
    return sum(c for (s,), c in totals.items() if s == "202")


def parse_app_logs(lines):
    """분석 성공·실패와 식사 알림 발송을 로그 줄로 센다. 한 줄은 처음 맞는 규칙 하나에만 센다."""
    ok, fail = {}, {}
    meal = {"runs": 0, "total": 0, "sent": 0, "failed": 0, "unregistered": 0}

    def add(d, key, amount=1):
        d[key] = d.get(key, 0) + amount

    for line in lines:
        if "분석을 완료했습니다" in line:
            m = re.search(r"sourceType=(\w+)", line)
            add(ok, m.group(1) if m else "?")
        elif m := re.search(r"failureCode=(\w+)", line):
            add(fail, m.group(1))
        elif "안전 차단으로" in line:
            add(fail, "CONTENT_NOT_RECOGNIZED")
        elif ("분석이 최종 실패" in line or "예상하지 못한 오류로 분석" in line
              or "Instagram 수집이 재시도 끝에 실패" in line):
            add(fail, "PROCESSING_FAILED")
        elif "stale Job 을 복구했습니다" in line or "대기 상한을 넘긴" in line:
            m = re.search(r"failed=(\d+)", line)
            if m and int(m.group(1)):
                add(fail, "PROCESSING_FAILED", int(m.group(1)))
        elif m := re.search(r"식사 알림을 보냈습니다\. total=(\d+), sent=(\d+), failed=(\d+), "
                            r"unregisteredTokens=(\d+)", line):
            meal["runs"] += 1
            for key, value in zip(("total", "sent", "failed", "unregistered"), m.groups()):
                meal[key] += int(value)
    return ok, fail, meal


def service_lines(g, start, end):
    lines = []
    try:
        requested = f"분석 요청 {n(analysis_requests(g, start, end))} → "
    except AuthError:
        raise
    except QueryError as e:
        lines.append((WARN, f"요청 수 조회 실패 ({e})"))
        requested = "분석 "

    try:
        raw = g.logs(LOGS, APP_LOG_QUERY, start, end, APP_LOG_LIMIT)
    except AuthError:
        raise
    except QueryError as e:
        lines.append((WARN, f"분석·알림 로그 조회 실패 ({e})"))
        return lines

    truncated = " (잘림)" if len(raw) >= APP_LOG_LIMIT else ""
    ok, fail, meal = parse_app_logs(line for _, line in raw)

    def detail(d):
        return " (" + ", ".join(f"{k} {v}" for k, v in sorted(d.items(), key=lambda x: -x[1])) + ")" if d else ""

    lines.append((PLAIN, f"{requested}성공 {n(sum(ok.values()))}{detail(ok)}"
                         f" · 실패 {n(sum(fail.values()))}{detail(fail)}{truncated}"))
    lines.append((PLAIN, f"식사 알림 {meal['runs']}회 → 발송 {n(meal['sent'])} · 실패 {n(meal['failed'])}"
                         f" · 끊긴 토큰 {n(meal['unregistered'])}{truncated}"))
    return lines


# ── 조립 ────────────────────────────────────────────────────────────────────

# 골든 시그널(오류·지연·트래픽·포화도) 순서로 묶는다. 판정 없는 줄(ℹ️)은 참고용이다.
SECTIONS = [
    ("가용성·오류", [
        ("외부 접속", check_probe),
        ("5xx·로그", check_errors),
        ("외부 API", check_external),
        ("4xx", info_4xx),
    ]),
    ("지연·트래픽", [
        ("트래픽", info_traffic),
        ("지연", info_latency),
    ]),
    ("자원 (24시간 최대)", [
        ("자원", check_resources),
    ]),
    ("운영", [
        ("앱 지표", check_app),
        ("배포·재시작", check_deploy),
        ("Worker", check_worker),
        ("백업", check_backup),
        ("울린 알림", check_alerts),
    ]),
]


def build_report(g, grafana_url, end):
    start = end - WINDOW

    def render(lines):
        return "\n".join(f"{mark} {text}" if mark else text for mark, text in lines)

    sections = []
    for title, checks in SECTIONS:
        lines = []
        for name, check in checks:
            try:
                lines.append(check(g, start, end))
            except AuthError:
                raise
            except QueryError as e:
                lines.append((WARN, f"{name} 조회 실패 ({e})"))
        sections.append((title, lines))
    sections.append(("서비스 (서버만 아는 것)", service_lines(g, start, end)))

    warns = sum(1 for _, lines in sections for mark, _ in lines if mark == WARN)
    header = "✅ 이상 없음" if warns == 0 else f"⚠️ 확인 필요 {warns}건"

    parts = [f"📋 prod 일일 보고 · {kst(start)} ~ {kst(end)} KST", header]
    for title, lines in sections:
        parts += ["", f"**{title}**", render(lines)]
    parts += ["", f"<{grafana_url}/d/starpick-overview>"]
    message = "\n".join(parts)
    # 목록은 항목마다 이미 잘라 두었다. 그래도 넘치면 끝을 자른다 — 헤더가 앞에 있어 판단은 남는다.
    if len(message) > MESSAGE_LIMIT:
        message = message[:MESSAGE_LIMIT - 1] + "…"
    return message, warns


def send_discord(webhook_url, content):
    body = json.dumps({"content": content, "allowed_mentions": {"parse": []}, "flags": 4}).encode()
    req = urllib.request.Request(webhook_url, data=body, method="POST", headers={
        "Content-Type": "application/json",
        # 기본 Python-urllib User-Agent 는 Discord 앞단(Cloudflare)이 막는다.
        "User-Agent": "starpick-daily-report",
    })
    with urllib.request.urlopen(req, timeout=TIMEOUT) as res:
        return res.status


def log(msg):
    print(f"{datetime.now(timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ')} daily-report: {msg}", flush=True)


def main():
    dry_run = "--dry-run" in sys.argv[1:]
    path = os.environ.get("DAILY_REPORT_ENV", "/etc/starpick/daily-report.env")
    try:
        cfg = load_config(path)
    except (OSError, ValueError) as e:
        # 웹훅을 모르니 Discord 로 알릴 수 없다. 보고가 안 온 것이 신호다.
        log(f"설정을 읽지 못했습니다 — {path} ({type(e).__name__})")
        return 1

    g = Grafana(cfg["GRAFANA_URL"], cfg["GRAFANA_TOKEN"])
    try:
        message, warns = build_report(g, cfg["GRAFANA_URL"], int(time.time()))
        code = 0
    except AuthError as e:
        message, warns, code = f"❌ prod 일일 보고를 만들지 못했습니다 — {e}", None, 1

    if dry_run:
        print(message)
        return code

    try:
        status = send_discord(cfg["DISCORD_WEBHOOK_URL"], message)
    except urllib.error.HTTPError as e:
        log(f"Discord 전송 실패 — 응답 {e.code}")
        return 1
    except (urllib.error.URLError, TimeoutError) as e:
        log(f"Discord 전송 실패 — {type(e).__name__}")
        return 1
    log(f"전송 완료 — Discord {status}, ⚠️ {warns if warns is not None else '보고 실패'}")
    return code


if __name__ == "__main__":
    sys.exit(main())
