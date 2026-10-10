-- V9__explore.sql — 직무 탐색 결과(2026-10-10, ADR-0031)
--
-- 학생이 쓴 경험 글과 고른 '하고 싶은 일' 카드를 AI가 공고와 함께 읽고 지원할 수 있는 자리 5곳을 고른다.
-- AI는 실행마다 순서가 조금 달라서 결과를 저장하고 다시 계산하지 않는다(E7). 계정당 마지막 1건만 둔다.
-- 경험 글은 학생이 동의(consent)했을 때만 받고, 전화번호·이메일·긴 숫자를 가린 뒤 저장한다.
-- 탈퇴·체험 계정 정리(app_user 삭제) 때 함께 지워진다. 학과·학년·평점은 여기에 두지 않는다(판정은 프로필로 다시 한다).

CREATE TABLE explore_run (
    id                bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id           bigint       NOT NULL UNIQUE REFERENCES app_user (id) ON DELETE CASCADE,
    round_id          smallint     NOT NULL REFERENCES recruit_round (id),
    experiences       text[]       NOT NULL DEFAULT '{}',
    card_texts        text[]       NOT NULL DEFAULT '{}',
    interest_text     varchar(200),
    source            varchar(10)  NOT NULL CHECK (source IN ('AI', 'RULE')),
    fallback_reason   varchar(20)
        CHECK (fallback_reason IN ('NO_KEY', 'LIMITED', 'AI_ERROR', 'VERIFY_FAILED', 'NO_CANDIDATES')),
    candidate_job_ids integer[]    NOT NULL DEFAULT '{}',
    eligible_count    smallint     NOT NULL CHECK (eligible_count >= 0),
    needs_check_count smallint     NOT NULL CHECK (needs_check_count >= 0),
    blocked_by        jsonb        NOT NULL DEFAULT '[]',
    model             varchar(60),
    prompt_version    varchar(12),
    created_at        timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT explore_run_fallback CHECK ((source = 'AI') = (fallback_reason IS NULL))
);
COMMENT ON TABLE  explore_run IS '직무 탐색 1회(계정당 마지막 1건). 경험 글은 동의했을 때만, 가린 뒤 저장. ADR-0031';
COMMENT ON COLUMN explore_run.experiences IS '해 본 일 0~3개(전화번호·이메일·8~10자리 숫자는 [가림])';
COMMENT ON COLUMN explore_run.card_texts IS '고른 하고 싶은 일 카드 글(기관 이름은 회사로 가린 직무 원문 항목)';
COMMENT ON COLUMN explore_run.candidate_job_ids IS '탐색 때 후보(ELIGIBLE·NEEDS_CHECK, 마감 전) 직무. 저장한 프로필이 없을 때 다시 판정 대신 쓴다';
COMMENT ON COLUMN explore_run.blocked_by IS '후보·결과가 없을 때 막은 요건별 직무 수 [{item, count}](#15와 같은 규칙)';
COMMENT ON COLUMN explore_run.prompt_version IS '탐색·설명 시스템 프롬프트 해시 앞 12자리';

CREATE TABLE explore_item (
    run_id         bigint       NOT NULL REFERENCES explore_run (id) ON DELETE CASCADE,
    rank           smallint     NOT NULL CHECK (rank BETWEEN 1 AND 5),
    job_id         integer      NOT NULL REFERENCES job (id) ON DELETE CASCADE,
    verdict        varchar(20)  NOT NULL CHECK (verdict IN ('ELIGIBLE', 'NEEDS_CHECK')),
    fit            varchar(10)  NOT NULL CHECK (fit IN ('STRONG', 'GOOD')),
    student_quote  varchar(300),
    job_quote      varchar(300),
    document_title varchar(200),
    page           smallint     CHECK (page > 0),
    reason         text         NOT NULL,
    PRIMARY KEY (run_id, rank),
    UNIQUE (run_id, job_id)
);
COMMENT ON TABLE  explore_item IS '탐색 결과 자리(순위 순). 구절은 서버가 원문과 대조해 통과한 것만. 직무별 탐색 수요 집계에도 쓴다';
COMMENT ON COLUMN explore_item.verdict IS '탐색 때 판정. 다시 볼 때는 저장한 프로필로 다시 판정한다';
COMMENT ON COLUMN explore_item.document_title IS 'job_quote가 든 문서(운영계획서, 규칙 추천이면 그 인용의 문서)';
COMMENT ON COLUMN explore_item.fit IS '1~2위 STRONG, 3~5위 GOOD(규칙 추천으로 대신했으면 HIGH → STRONG, MEDIUM → GOOD)';

CREATE TABLE explore_why (
    run_id     bigint      NOT NULL REFERENCES explore_run (id) ON DELETE CASCADE,
    job_id     integer     NOT NULL REFERENCES job (id) ON DELETE CASCADE,
    body       jsonb       NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (run_id, job_id)
);
COMMENT ON TABLE  explore_why IS '직무 상세 왜 맞나요 {summary, points, tryNew, prepare}(검증 통과분). 1~3위는 탐색 때, 나머지는 처음 열 때 만든다';
