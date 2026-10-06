// 실행 위치: load-test/
//   BASE_URL=http://localhost:8080 k6 run create-guests.js
// 게스트를 만들고 토큰을 guests.json 에 저장한다. 토큰은 화면에 출력하지 않는다.
import exec from 'k6/execution';
import { createGuest, toStored } from './lib.js';

const COUNT = Number(__ENV.GUEST_COUNT || 10);

// 정리하지 않은 게스트가 남아 있으면 덮어쓰지 않는다. 덮어쓰면 그 계정을 탈퇴시킬 토큰을 잃는다.
// 파일이 없을 때만 새로 만든다. 내용이 깨져 있으면 여기서 멈춘다.
let raw = null;
try {
  raw = open('./guests.json');
} catch (e) {
  // 파일 없음
}
const existing = raw == null ? [] : JSON.parse(raw);
if (existing.length > 0) {
  throw new Error(`guests.json 에 게스트 ${existing.length}명이 남아 있습니다. cleanup.js 를 먼저 실행하세요.`);
}

export const options = { vus: 1, iterations: 1 };

// 중간에 실패해도 이미 만든 게스트를 반환해야 handleSummary 가 토큰을 저장하고 cleanup.js 로 지울 수 있다
export function setup() {
  const guests = [];
  try {
    for (let i = 0; i < COUNT; i++) {
      guests.push(createGuest());
    }
    return { guests };
  } catch (e) {
    return { guests, error: e.message };
  }
}

export default function (data) {
  if (data.error) exec.test.abort(data.error);
}

export function handleSummary(data) {
  const setup = data.setup_data || {};
  const guests = setup.guests || [];
  let stdout = `게스트 ${guests.length}명 생성. userId: ${guests.map((g) => g.userId).join(',')}\n`;
  if (setup.error) stdout += `실패: ${setup.error}. 만든 게스트는 guests.json 에 저장했으니 cleanup.js 로 정리하세요.\n`;
  const out = { stdout };
  if (guests.length > 0) {
    out['guests.json'] = JSON.stringify(guests.map(toStored), null, 2);
  }
  return out;
}
