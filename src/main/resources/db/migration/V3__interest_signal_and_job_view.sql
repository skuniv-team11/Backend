-- V3__interest_signal_and_job_view.sql — 모집 신호를 '관심' 하나로, 직무 상세 조회수(2026-10-05, ADR-0019)
--
-- 1) 관심 = 내 지망에 담은 사람 수. 예전의 지원 의사(intent_count)를 관심으로 부르고, 예전 관심(interest_count — 근거 없는
--    가상 여분)은 없앤다. 리플레이 가상 값에 실제 사용자가 담은 수(plan_item, 1인 1표, 체험 계정 포함)를 더해 보여 준다.
--    순서: 예전 관심 칸을 지우고 → 지원 의사 칸 이름을 관심으로 바꾼다. 행 값은 R__seed.sql이 다시 넣는다.
-- 2) 직무 상세를 연 학생 수(조회수). 계정마다 직무별로 하루(한국 시간) 한 번만 센다. 계정이 지워져도 조회수는 남기고
--    계정 연결만 끊는다(user_id NULL).
-- V1의 '실행 중에 쓰는 건 계정 3개뿐' 주석은 이 파일로 바뀐다(적용한 마이그레이션은 고치지 않는다, ADR-0010).

ALTER TABLE replay_signal DROP COLUMN interest_count;
ALTER TABLE replay_signal RENAME COLUMN intent_count TO interest_count;
ALTER TABLE replay_signal RENAME CONSTRAINT replay_signal_intent_count_check TO replay_signal_interest_count_check;
ALTER TABLE replay_signal RENAME CONSTRAINT replay_signal_intent_count_not_null TO replay_signal_interest_count_not_null; -- Postgres 18은 NOT NULL에도 이름이 있다
COMMENT ON TABLE  replay_signal IS '모집기간 리플레이용 가상 관심(내 지망에 담은 사람) 수(생성기 시드 고정). 그날 새로 생긴 수이고 누적이 아니다. 직무별 합 = 최종 배정 수. API가 asOf까지 합산하고 실제 담은 수를 더한다. 마감(closes_on) 뒤에는 행을 만들지 않는다';
COMMENT ON COLUMN replay_signal.interest_count IS '그날 새로 내 지망에 담은 사람 수(가상)';

COMMENT ON TABLE  plan_item IS 'S3에서 담은 직무. rank NULL = 담기만, 1~3 = 지망 순위(S5). 담은 사람 수(순위 무관, 1인 1표, 체험 계정 포함)가 모집 신호의 실제 관심이 된다(ADR-0019)';

CREATE TABLE job_view (
    id        bigint  GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    job_id    integer NOT NULL REFERENCES job(id) ON DELETE CASCADE,
    user_id   bigint  REFERENCES app_user(id) ON DELETE SET NULL,
    viewed_on date    NOT NULL,
    CONSTRAINT job_view_once_a_day UNIQUE (job_id, user_id, viewed_on)
);
COMMENT ON TABLE  job_view IS '직무 상세 조회(#17)를 학생 계정마다 직무별로 하루 한 번 기록한다. 조회수 = 행 수(ADR-0019). 실제 값만 둔다(가상 조회수 없음)';
COMMENT ON COLUMN job_view.user_id IS '조회한 학생. 계정이 지워지면 NULL — 조회수는 남고 누가 봤는지는 남지 않는다';
COMMENT ON COLUMN job_view.viewed_on IS '조회한 날(한국 시간). 같은 계정·직무·날은 한 행만';
