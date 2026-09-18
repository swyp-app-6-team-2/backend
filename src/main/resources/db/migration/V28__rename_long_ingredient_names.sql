-- 재료명이 9자를 넘으면 재료 관리 화면에서 잘린다(QA 2026-09-16, 이슈 #117).
-- 이름은 PM 소유이고 시트 표기를 그대로 쓴다. PM이 시트 ingre_name_R_0916 열에 바꿀 이름을 확정했다.
-- aliases 는 건드리지 않는다. 이름에서 빠지는 '앞다리살'·'불고기용'이 이미 들어 있어 매칭이 유지된다.
update ingredient set name = '돼지고기(앞다리)' where code = 'MET004';
update ingredient set name = '쇠고기(등심)' where code = 'MET006';
