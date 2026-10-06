import http from 'k6/http';
import { check } from 'k6';

export const BASE_URL = (__ENV.BASE_URL || '').replace(/\/+$/, '');
if (!BASE_URL) {
  throw new Error('BASE_URL 환경변수가 필요합니다. 예: BASE_URL=http://localhost:8080');
}

const JSON_HEADERS = { 'Content-Type': 'application/json' };

export function api(path) {
  return `${BASE_URL}/api/v1${path}`;
}

export function authParams(accessToken, name, extraHeaders = {}) {
  return {
    headers: { ...JSON_HEADERS, ...extraHeaders, Authorization: `Bearer ${accessToken}` },
    tags: { name },
  };
}

// 실패 원인은 상태와 data.code 만 남긴다. 응답 본문에 토큰이 들어 있을 수 있어 통째로 출력하지 않는다.
function describe(res) {
  let code = null;
  try {
    code = res.json('data.code');
  } catch (e) {
    // JSON 이 아닌 응답
  }
  return `HTTP ${res.status}${code ? ` ${code}` : ''}`;
}

/** setup 처럼 실패하면 더 진행할 수 없는 요청에 쓴다. */
export function must(res, expectedStatus, what) {
  if (res.status !== expectedStatus) {
    throw new Error(`${what} 실패: ${describe(res)} (기대 ${expectedStatus})`);
  }
  return res.json('data');
}

/** 상태 코드와 Envelope status 가 기대값과 같은지 검사한다. extra 로 응답별 검사를 더한다. */
export function checkEnvelope(res, expectedStatus, extra = {}) {
  return check(res, {
    [`status ${expectedStatus}`]: (r) => r.status === expectedStatus,
    'envelope status = HTTP status': (r) => {
      try {
        return r.json('status') === r.status;
      } catch (e) {
        return false;
      }
    },
    ...extra,
  });
}

export function createGuest() {
  const res = http.post(api('/auth/guest'), null, { tags: { name: 'setup' } });
  const data = must(res, 201, '게스트 생성');
  return { userId: data.userId, accessToken: data.accessToken, refreshToken: data.refreshToken };
}

/** refresh 토큰은 재발급마다 바뀐다. 반환한 guest 를 guests.json 에 다시 써야 다음 실행이 이어진다. */
export function refresh(guest) {
  const res = http.post(api('/auth/token/refresh'), JSON.stringify({ refreshToken: guest.refreshToken }), {
    headers: JSON_HEADERS,
    tags: { name: 'setup' },
  });
  const data = must(res, 200, `토큰 재발급(userId=${guest.userId})`);
  return { ...guest, accessToken: data.accessToken, refreshToken: data.refreshToken };
}

/** guests.json 에 쓰는 항목만 남긴다. setup 이 붙인 레시피 id 같은 값은 저장하지 않는다. */
export function toStored(guest) {
  const stored = { userId: guest.userId, accessToken: guest.accessToken, refreshToken: guest.refreshToken };
  if (guest.ingestionJobId != null) stored.ingestionJobId = guest.ingestionJobId;
  return stored;
}
