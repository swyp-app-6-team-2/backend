// 실행 위치: load-test/
//   BASE_URL=http://localhost:8080 k6 run create-guests.js
// 게스트를 만들고 토큰을 guests.json 에 저장한다. 토큰은 화면에 출력하지 않는다.
import { createGuest, toStored } from './lib.js';

const COUNT = Number(__ENV.GUEST_COUNT || 10);

// 정리하지 않은 게스트가 남아 있으면 덮어쓰지 않는다. 덮어쓰면 그 계정을 탈퇴시킬 토큰을 잃는다.
let existing = [];
try {
  existing = JSON.parse(open('./guests.json'));
} catch (e) {
  // 파일이 없으면 새로 만든다
}
if (existing.length > 0) {
  throw new Error(`guests.json 에 게스트 ${existing.length}명이 남아 있습니다. cleanup.js 를 먼저 실행하세요.`);
}

export const options = { vus: 1, iterations: 1 };

export function setup() {
  const guests = [];
  for (let i = 0; i < COUNT; i++) {
    guests.push(createGuest());
  }
  return { guests };
}

export default function () {}

export function handleSummary(data) {
  const guests = (data.setup_data && data.setup_data.guests) || [];
  const out = { stdout: `게스트 ${guests.length}명 생성. userId: ${guests.map((g) => g.userId).join(',')}\n` };
  if (guests.length > 0) {
    out['guests.json'] = JSON.stringify(guests.map(toStored), null, 2);
  }
  return out;
}
