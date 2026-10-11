-- V13__explore_links.sql — 직무 탐색: AI는 짝과 종류만, 순서는 규칙(2026-10-11, ADR-0038)
--
-- 지금까지는 AI가 5곳의 순서를 정하고 맞는 정도를 순위로 붙였다(1~2위 STRONG, 3~5위 GOOD). 이제 AI는 학생 글 ↔ 업무의
-- 짝과 그 종류(같은 일 · 해 본 일의 능력이 쓰이는 일 · 하고 싶은 일과 같은 일)만 내고, 서버가 대조한 뒤 규칙으로 줄을 세운다.
-- 그래서 explore_item.fit은 순위가 아니라 근거 단계이고, 그 근거가 학생의 무엇(해 본 일 · 고른 카드 · 관심 분야)에서 왔는지와
-- 대조를 통과한 짝 전부를 함께 둔다.
--
-- 지난 탐색 결과(순위로 붙인 STRONG·GOOD)는 새 단계로 옮길 수 없어 지운다. 학생은 다시 탐색하면 된다(계정당 1건,
-- 다시 계산해 만드는 값이다). '왜 맞나요'는 explore_run을 따라 함께 지워진다.

DELETE FROM explore_run;

ALTER TABLE explore_item DROP CONSTRAINT explore_item_fit_check;
ALTER TABLE explore_item
    ADD CONSTRAINT explore_item_fit_check CHECK (fit IN ('SAME_TASK', 'TRANSFER', 'WISH', 'NONE')),
    ADD COLUMN basis varchar(10) CHECK (basis IN ('EXPERIENCE', 'CARD', 'INTEREST')),
    ADD COLUMN links jsonb NOT NULL DEFAULT '[]';

COMMENT ON COLUMN explore_item.fit IS
    '근거 단계: SAME_TASK 해 본 일과 같은 일 · TRANSFER 해 본 일의 능력이 쓰이는 일 · WISH 하고 싶은 일(고른 카드·관심 분야)과 같은 일 · NONE 규칙 추천으로 대신(AI 연결 없음)';
COMMENT ON COLUMN explore_item.basis IS '대표 근거의 학생 쪽 출처(해 본 일 · 고른 카드 · 관심 분야). 규칙 추천이면 null';
COMMENT ON COLUMN explore_item.links IS
    '대조를 통과한 짝 전부 [{kind, basis, studentQuote, jobQuote, documentTitle, page}] — 단계 높은 것부터';
COMMENT ON COLUMN explore_item.student_quote IS '대표 짝의 학생 구절(links의 첫 짝). 규칙 추천이면 null';
