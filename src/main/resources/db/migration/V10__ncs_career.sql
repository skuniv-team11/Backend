-- V10__ncs_career.sql — 실습 뒤 커리어: 직무 ↔ NCS 세분류 · 능력단위 · 넓혀 갈 세분류 · 이어지는 직업, 커리어 리포트(2026-10-10, ADR-0032)
--
-- NCS(국가직무능력표준) 값은 실행 중에 부르지 않고 pipeline/seed/ncs_seed.py가 미리 만든 시드(R__seed.sql)로 넣는다(ADR-0002).
-- 직무마다 세분류 하나와 세분류마다 넓혀 갈 세분류 3개는 사람이 골랐다(curated). 직업은 한국고용정보원 '직업능력 코드매핑정보'
-- (NCS ↔ 한국고용직업분류)를 쓰고, 연계표에 없는 세분류만 사람이 같은 표의 직업에서 골라 더했다(source CURATED).
-- 커리어 리포트는 학생이 수행결과보고서 '실습 내용'을 붙여 넣으면 AI가 그 직무 세분류의 능력단위 중 다룬 것을 고르고,
-- 서버가 학생 구절을 원문과 대조해 통과한 것만 둔다. 계정당 마지막 1건, 탈퇴 때 함께 지운다.

CREATE TABLE ncs_subcategory (
    code        char(8)      PRIMARY KEY,
    large_name  varchar(50)  NOT NULL,
    middle_name varchar(50)  NOT NULL,
    small_code  char(6)      NOT NULL,
    small_name  varchar(50)  NOT NULL,
    name        varchar(100) NOT NULL,
    CONSTRAINT ncs_subcategory_small CHECK (left(code, 6) = small_code)
);
COMMENT ON TABLE ncs_subcategory IS 'NCS 세분류(8자리 = 대2·중2·소2·세2). 직무에 고른 것과 넓혀 갈 것만 넣는다';

CREATE TABLE ncs_unit (
    code             varchar(20)  PRIMARY KEY,
    subcategory_code char(8)      NOT NULL REFERENCES ncs_subcategory (code) ON DELETE CASCADE,
    seq              smallint     NOT NULL CHECK (seq > 0),
    name             varchar(200) NOT NULL,
    level            smallint     CHECK (level BETWEEN 1 AND 8),
    definition       text
);
CREATE INDEX ncs_unit_subcategory ON ncs_unit (subcategory_code, seq);
COMMENT ON TABLE  ncs_unit IS 'NCS 능력단위(예: 0201030102_21v5). 구버전은 빼고, 이름이 같은 단위는 최신 개정 하나만';
COMMENT ON COLUMN ncs_unit.seq IS '세분류 안 능력단위 번호(코드 9~10자리)';
COMMENT ON COLUMN ncs_unit.level IS 'NCS 수준 1~8. 원본에 수준이 없으면(0) NULL';

CREATE TABLE occupation (
    code char(4)      PRIMARY KEY,
    name varchar(100) NOT NULL
);
COMMENT ON TABLE occupation IS '한국고용직업분류(KECO) 직업. 직업능력 코드매핑정보(한국고용정보원, 2025-11-26)에 있는 것만';

CREATE TABLE ncs_occupation (
    subcategory_code char(8)     NOT NULL REFERENCES ncs_subcategory (code) ON DELETE CASCADE,
    occupation_code  char(4)     NOT NULL REFERENCES occupation (code) ON DELETE CASCADE,
    source           varchar(10) NOT NULL CHECK (source IN ('KEIS', 'CURATED')),
    PRIMARY KEY (subcategory_code, occupation_code)
);
COMMENT ON COLUMN ncs_occupation.source IS 'KEIS 공식 연계표(세분류 또는 그 소분류) · CURATED 연계표에 없어 사람이 같은 표의 직업에서 고름';

CREATE TABLE job_ncs (
    job_id           integer      PRIMARY KEY REFERENCES job (id) ON DELETE CASCADE,
    subcategory_code char(8)      NOT NULL REFERENCES ncs_subcategory (code) ON DELETE CASCADE,
    note             varchar(200)
);
COMMENT ON TABLE job_ncs IS '직무 → NCS 세분류 하나(사람이 직무 원문과 능력단위를 보고 고름, curated/job_ncs.csv)';

CREATE TABLE ncs_expand (
    from_code char(8)     NOT NULL REFERENCES ncs_subcategory (code) ON DELETE CASCADE,
    rank      smallint    NOT NULL CHECK (rank BETWEEN 1 AND 3),
    to_code   char(8)     NOT NULL REFERENCES ncs_subcategory (code) ON DELETE CASCADE,
    relation  varchar(12) NOT NULL CHECK (relation IN ('SAME_SMALL', 'SAME_MIDDLE', 'OTHER')),
    PRIMARY KEY (from_code, rank),
    UNIQUE (from_code, to_code),
    CHECK (from_code <> to_code)
);
COMMENT ON TABLE  ncs_expand IS '실습 뒤 넓혀 갈 세분류 3개(사람이 고름, curated/ncs_expand.csv)';
COMMENT ON COLUMN ncs_expand.relation IS 'SAME_SMALL 같은 소분류 · SAME_MIDDLE 같은 중분류 · OTHER 다른 분야(코드로 정함)';

CREATE TABLE ncs_unit_link (
    from_code char(8)      NOT NULL,
    to_code   char(8)      NOT NULL,
    from_unit varchar(20)  NOT NULL REFERENCES ncs_unit (code) ON DELETE CASCADE,
    to_unit   varchar(20)  NOT NULL REFERENCES ncs_unit (code) ON DELETE CASCADE,
    note      varchar(100),
    checked   boolean      NOT NULL DEFAULT false,
    PRIMARY KEY (from_code, to_code, to_unit),
    FOREIGN KEY (from_code, to_code) REFERENCES ncs_expand (from_code, to_code) ON DELETE CASCADE,
    CONSTRAINT ncs_unit_link_units CHECK (left(from_unit, 8) = from_code AND left(to_unit, 8) = to_code)
);
COMMENT ON TABLE  ncs_unit_link IS '능력단위끼리 연결: 직무 세분류 단위 → 넓혀 갈 세분류 단위(넓힘마다 최대 5개, curated/ncs_unit_links.csv)';
COMMENT ON COLUMN ncs_unit_link.checked IS 'false AI 초안(ncs_links.py) · true 사람이 확인함. 응답에 그대로 내보낸다';

CREATE TABLE career_report (
    id               bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id          bigint       NOT NULL UNIQUE REFERENCES app_user (id) ON DELETE CASCADE,
    job_id           integer      NOT NULL REFERENCES job (id) ON DELETE CASCADE,
    subcategory_code char(8)      NOT NULL REFERENCES ncs_subcategory (code) ON DELETE CASCADE,
    practice_text    text         NOT NULL,
    source           varchar(10)  NOT NULL CHECK (source IN ('AI', 'NONE')),
    fallback_reason  varchar(20)
        CHECK (fallback_reason IN ('NO_KEY', 'LIMITED', 'AI_ERROR', 'VERIFY_FAILED')),
    model            varchar(60),
    prompt_version   varchar(12),
    created_at       timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT career_report_fallback CHECK ((source = 'AI') = (fallback_reason IS NULL))
);
COMMENT ON TABLE  career_report IS '커리어 리포트(계정당 마지막 1건). 실습 내용은 동의했을 때만, 전화·이메일·긴 숫자를 가린 뒤 저장';
COMMENT ON COLUMN career_report.source IS 'AI 능력단위를 골랐음 · NONE AI 없이(키 없음·한도·실패·대조 0개) 그 세분류 능력단위 목록만';

CREATE TABLE career_report_unit (
    report_id     bigint       NOT NULL REFERENCES career_report (id) ON DELETE CASCADE,
    unit_code     varchar(20)  NOT NULL REFERENCES ncs_unit (code) ON DELETE CASCADE,
    student_quote varchar(300) NOT NULL,
    reason        text         NOT NULL,
    PRIMARY KEY (report_id, unit_code)
);
COMMENT ON TABLE career_report_unit IS '실습에서 다룬 능력단위. 학생 구절은 실습 내용 원문과 대조해 통과한 것만';
