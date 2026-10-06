// 실행 위치: load-test/
//   BASE_URL=http://localhost:8080 k6 run cleanup.js
// guests.json 의 게스트를 모두 탈퇴시킨다. 탈퇴가 레시피(GCS 이미지 포함)·분석 작업·업로드·알림 데이터를 함께 지운다.
// 실패한 게스트만 guests.json 에 남긴다. 전부 성공하면 빈 배열이 된다.
import http from 'k6/http';
import { api, refresh, toStored } from './lib.js';

const guests = JSON.parse(open('./guests.json'));

export const options = { vus: 1, iterations: 1, setupTimeout: '5m' };

export function setup() {
  const failed = [];
  const done = [];
  for (const guest of guests) {
    let current = guest;
    try {
      current = refresh(guest);
    } catch (e) {
      // 재발급이 막혀도 access 토큰이 아직 유효하면 탈퇴할 수 있다
      console.warn(`${e.message}. 저장된 access 토큰으로 탈퇴를 시도합니다.`);
    }
    const res = http.del(api('/users/me'), null, {
      headers: { Authorization: `Bearer ${current.accessToken}` },
      tags: { name: 'setup' },
    });
    if (res.status === 200) {
      done.push(guest.userId);
    } else {
      console.error(`탈퇴 실패: userId=${guest.userId}, HTTP ${res.status}`);
      failed.push(current);
    }
  }
  return { done, failed };
}

export default function () {}

export function handleSummary(data) {
  // setup 이 중간에 실패하면 원래 파일을 건드리지 않는다
  if (!data.setup_data) return { stdout: 'setup 이 끝나지 않아 guests.json 을 그대로 둡니다.\n' };
  const { done, failed } = data.setup_data;
  return {
    stdout: `탈퇴 완료 ${done.length}명: ${done.join(',')}\n탈퇴 실패 ${failed.length}명: ${failed.map((g) => g.userId).join(',')}\n`,
    'guests.json': JSON.stringify(failed.map(toStored), null, 2),
  };
}
