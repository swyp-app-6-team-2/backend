// 실행 위치: load-test/2026-10-06-round2/
//   mkdir -p results
//   SCENARIOS=smoke BASE_URL=... k6 run load.js   # VU 1명이 각 요청을 한 번씩
//   BASE_URL=... k6 run load.js                   # 본 측정
// 1회차 후보(목록 조회) 개선 후 재측정. 데이터·setup 은 1회차와 같고 시나리오만 다르다.
// 목록·검색은 요청률을 1회차 처리량(초당 약 9.4건)에 고정한다. 빨라진 만큼 요청을 더 보내면
// 서명 수가 늘어 dev·prod 가 같이 쓰는 IAM 서명 한도(분당 60,000건)를 넘을 수 있기 때문이다.
import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';
import { api, authParams, checkEnvelope, must, refresh, toStored } from '../lib.js';

const SMOKE = __ENV.SCENARIOS === 'smoke';
const VUS = Number(__ENV.VUS || 10);
const RECIPES_PER_USER = 20;
const COOK_HISTORIES = 5;
const WINDOW_SECONDS = 60;
const GAP_SECONDS = 15;
const LIST_RATE = Number(__ENV.LIST_RATE || 9);

const guests = JSON.parse(open('../guests.json'));
const COVER = open('../2026-10-01-round1/cover.jpg', 'b');

if (guests.length < VUS) {
  throw new Error(`게스트 ${guests.length}명으로는 VU ${VUS}명을 짝지을 수 없습니다.`);
}
if (guests.some((g) => g.ingestionJobId == null)) {
  throw new Error('ingestionJobId 가 없는 게스트가 있습니다. seed.sql 을 먼저 실행하세요.');
}

// A1 과 A2 는 서버에서 같은 시리즈(GET /api/v1/recipes)라 사이에 A3 를 둔다. 서버 p95 창이 약 2분이다.
// A3(상세)는 코드가 바뀌지 않은 대조군이다. 1회차와 비슷해야 환경이 같다고 본다.
const ORDER = [
  { id: 'a1', exec: 'listRecipes', rate: true },
  { id: 'a3', exec: 'getRecipe', rate: false },
  { id: 'a2', exec: 'searchRecipes', rate: true },
];

const NAMES = ['GET /recipes', 'GET /recipes?searchQuery', 'GET /recipes/{id}'];

function buildScenarios() {
  if (SMOKE) {
    return { smoke: { executor: 'per-vu-iterations', vus: 1, iterations: 1, exec: 'smoke' } };
  }
  const scenarios = {};
  ORDER.forEach((s, i) => {
    const common = {
      duration: `${WINDOW_SECONDS}s`,
      // 0 이 아니면 앞 구간 VU 가 남아 다음 구간과 겹친다
      gracefulStop: '0s',
      startTime: `${i * (WINDOW_SECONDS + GAP_SECONDS)}s`,
      exec: s.exec,
    };
    scenarios[s.id] = s.rate
      ? { ...common, executor: 'constant-arrival-rate', rate: LIST_RATE, timeUnit: '1s', preAllocatedVUs: VUS, maxVUs: VUS * 2 }
      : { ...common, executor: 'constant-vus', vus: VUS };
  });
  return scenarios;
}

// 태그별 값을 요약에 남기려면 해당 하위 지표에 threshold 가 있어야 한다. 판정용이 아니다.
function buildThresholds() {
  const thresholds = { checks: ['rate==1'] };
  for (const name of NAMES) {
    thresholds[`http_req_duration{name:${name}}`] = ['max>=0'];
    thresholds[`http_req_failed{name:${name}}`] = ['rate>=0'];
  }
  if (!SMOKE) {
    for (const s of ORDER) thresholds[`checks{scenario:${s.id}}`] = ['rate>=0'];
    // 요청률 고정 구간은 VU 가 모자라면 요청을 보내지 못하고 이 지표에만 남긴다.
    // 그러면 지연이 남은 요청으로만 계산되므로 한 건이라도 있으면 실패로 본다.
    thresholds.dropped_iterations = ['count==0'];
  }
  return thresholds;
}

export const options = {
  scenarios: buildScenarios(),
  thresholds: buildThresholds(),
  setupTimeout: '10m',
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max', 'count'],
};

// ---------- 요청 ----------

function recipeBody(title, coverImageKey, ingredientIds) {
  return JSON.stringify({
    title,
    categoryCode: 'KOREAN',
    servings: 2,
    cookTimeMinutes: 15,
    coverImageKey,
    ingredients: ingredientIds.map((id, i) => ({ ingredientId: id, name: `재료${i + 1}`, amountText: '1개' })),
    steps: [1, 2, 3, 4, 5].map((i) => ({ content: `단계 ${i}` })),
  });
}

function issueUpload(guest, name) {
  const body = JSON.stringify({ purpose: 'RECIPE_COVER', contentType: 'image/jpeg' });
  return http.post(api('/uploads/images'), body, authParams(guest.accessToken, name));
}

// 발급 응답의 헤더를 그대로 붙인다. 빠지면 GCS 가 403 을 낸다.
function putCover(upload, name) {
  return http.put(upload.uploadUrl, COVER, { headers: upload.uploadHeaders, tags: { name } });
}

function postRecipe(guest, title, coverImageKey, ingredientIds, name) {
  return http.post(api('/recipes'), recipeBody(title, coverImageKey, ingredientIds), authParams(guest.accessToken, name));
}

// ---------- setup: 사용자당 레시피 20개 + 요리 기록 5건. 이미 있으면 모자란 만큼만 채운다 ----------

function seedUser(guest, ingredientIds) {
  const p = authParams(guest.accessToken, 'setup');
  const total = must(http.get(api('/recipes?size=1'), p), 200, '레시피 수 조회').totalCount;
  for (let n = total + 1; n <= RECIPES_PER_USER; n++) {
    const upload = must(issueUpload(guest, 'setup'), 200, '업로드 URL 발급');
    const put = putCover(upload, 'setup');
    if (put.status !== 200) throw new Error(`GCS PUT 실패: HTTP ${put.status}`);
    must(postRecipe(guest, `김치볶음밥 ${n}`, upload.objectKey, ingredientIds, 'setup'), 201, '레시피 생성');
  }

  // 시드 레시피는 가장 먼저 만든 20개다. 쓰기 시나리오가 중간에 끊겨 남긴 레시피는 그 뒤에 온다.
  const recipes = must(http.get(api(`/recipes?size=${RECIPES_PER_USER}&sort=OLDEST`), p), 200, '레시피 목록 조회').recipes;
  if (recipes.length !== RECIPES_PER_USER) {
    throw new Error(`userId=${guest.userId} 레시피가 ${recipes.length}개입니다(기대 ${RECIPES_PER_USER})`);
  }
  const recipeIds = recipes.map((r) => r.recipeId);
  const cookRecipeId = recipeIds[0];

  const histories = must(http.get(api(`/recipes/${cookRecipeId}/cook-histories`), p), 200, '요리 기록 조회');
  for (let i = histories.length; i < COOK_HISTORIES; i++) {
    must(http.post(api(`/recipes/${cookRecipeId}/cook-histories`), '{}', p), 201, '요리 기록 생성');
  }
  return { ...guest, recipeIds, cookRecipeId };
}

export function setup() {
  // 재발급하면 이전 refresh 토큰이 무효가 된다. 중간에 실패해도 새 토큰을 handleSummary 까지 넘겨 guests.json 에 남긴다.
  const fresh = guests.slice();
  try {
    for (let i = 0; i < fresh.length; i++) fresh[i] = refresh(fresh[i]);

    const ingredients = must(http.get(api('/ingredients'), authParams(fresh[0].accessToken, 'setup')), 200, '재료 조회').ingredients;
    const ingredientIds = ingredients.slice(0, 5).map((i) => i.ingredientId);

    for (let i = 0; i < fresh.length; i++) fresh[i] = seedUser(fresh[i], ingredientIds);
    return { guests: fresh, ingredientIds, scenarioStartMs: Date.now() };
  } catch (e) {
    return { guests: fresh, setupError: e.message };
  }
}

// ---------- 시나리오 ----------

function guestOf(data) {
  if (data.setupError) exec.test.abort(`setup 실패: ${data.setupError}`);
  return data.guests[(__VU - 1) % data.guests.length];
}

function safe(fn) {
  return (r) => {
    try {
      return fn(r);
    } catch (e) {
      return false;
    }
  };
}

// 서명이 실패하면 서버는 coverImageUrl 을 null 로 두고 200 을 준다. 그러면 오히려 빨라져 순위가 뒤집히므로 실패로 센다.
const FULL_PAGE = {
  [`레시피 ${RECIPES_PER_USER}건`]: safe((r) => r.json('data.recipes').length === RECIPES_PER_USER),
  '모든 coverImageUrl 있음': safe((r) => r.json('data.recipes').every((x) => x.coverImageUrl != null)),
};

export function listRecipes(data) {
  const g = guestOf(data);
  checkEnvelope(http.get(api('/recipes'), authParams(g.accessToken, 'GET /recipes')), 200, FULL_PAGE);
}

export function searchRecipes(data) {
  const g = guestOf(data);
  const url = api(`/recipes?searchQuery=${encodeURIComponent('김치')}`);
  checkEnvelope(http.get(url, authParams(g.accessToken, 'GET /recipes?searchQuery')), 200, FULL_PAGE);
}

export function getRecipe(data) {
  const g = guestOf(data);
  const id = g.recipeIds[Math.floor(Math.random() * g.recipeIds.length)];
  checkEnvelope(http.get(api(`/recipes/${id}`), authParams(g.accessToken, 'GET /recipes/{id}')), 200, {
    'coverImageUrl 있음': safe((r) => r.json('data.coverImageUrl') != null),
  });
}






export function smoke(data) {
  listRecipes(data);
  getRecipe(data);
  searchRecipes(data);
}

// ---------- 결과 ----------

function ms(v) {
  return v == null ? '-' : `${Math.round(v)}`;
}

function apiTable(metrics) {
  const rows = ['| API | 요청 | 실패율 | k6 p50 | k6 p95 | k6 p99 | k6 max |', '|---|---|---|---|---|---|---|'];
  for (const name of NAMES) {
    const d = metrics[`http_req_duration{name:${name}}`];
    if (!d || !d.values.count) continue;
    const f = metrics[`http_req_failed{name:${name}}`];
    const rate = f ? `${(f.values.rate * 100).toFixed(2)}%` : '-';
    const v = d.values;
    rows.push(`| ${name} | ${v.count} | ${rate} | ${ms(v.med)} | ${ms(v['p(95)'])} | ${ms(v['p(99)'])} | ${ms(v.max)} |`);
  }
  return rows.join('\n');
}

// 서버 p95 는 최근 약 2분 창의 값이라 구간 종료 직후(+20초)에 읽는다
function windowTable(metrics, startMs) {
  const rows = ['| 구간 | 시작(UTC) | 종료(UTC) | 조회 시각(UTC) | 조회 unix | check 통과율 |', '|---|---|---|---|---|---|'];
  ORDER.forEach((s, i) => {
    const start = startMs + i * (WINDOW_SECONDS + GAP_SECONDS) * 1000;
    const end = start + WINDOW_SECONDS * 1000;
    const query = end + 20 * 1000;
    const c = metrics[`checks{scenario:${s.id}}`];
    const rate = c ? `${(c.values.rate * 100).toFixed(2)}%` : '-';
    const iso = (t) => new Date(t).toISOString().slice(0, 19) + 'Z';
    rows.push(`| ${s.id.toUpperCase()} | ${iso(start)} | ${iso(end)} | ${iso(query)} | ${Math.floor(query / 1000)} | ${rate} |`);
  });
  return rows.join('\n');
}

export function handleSummary(data) {
  const setup = data.setup_data || {};
  const lines = [];
  if (setup.setupError) lines.push(`setup 실패: ${setup.setupError}`);
  const checks = data.metrics.checks;
  if (checks) lines.push(`check 통과율: ${(checks.values.rate * 100).toFixed(2)}% (실패 ${checks.values.fails}건)`);
  if (!SMOKE) {
    const dropped = data.metrics.dropped_iterations;
    lines.push(`보내지 못한 요청(dropped_iterations): ${dropped ? dropped.values.count : 0}건`);
  }
  lines.push('', apiTable(data.metrics));
  if (!SMOKE && setup.scenarioStartMs) lines.push('', windowTable(data.metrics, setup.scenarioStartMs));

  // setup_data 에는 토큰이 들어 있어 원본 결과에서 뺀다
  const { setup_data: _, ...raw } = data;
  const out = { stdout: lines.join('\n') + '\n', 'results/summary.json': JSON.stringify(raw, null, 2) };
  // setup 이 재발급한 토큰을 저장한다. setup 이 아예 끝나지 않았으면 원래 파일을 건드리지 않는다.
  if (setup.guests) out['../guests.json'] = JSON.stringify(setup.guests.map(toStored), null, 2);
  return out;
}
