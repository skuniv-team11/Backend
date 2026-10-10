-- V11__internship_flow.sql — 지원서(별지 제5호) → 접수 → 매칭 → 면접·선발 → 마무리, 회차 일정, 체험 묶음(2026-10-10, ADR-0033)
--
-- 학생이 플랫폼에서 지원서를 쓰고 학과(부)장 승인 링크를 받아 내면, 센터가 접수·보완 요청 → 1~3지망 중 하나로 매칭 → 기관이
-- 알려 준 면접·결과를 넣고 알림 → 마무리 서류를 모아 학점 인정 명단을 만든다. 기관 계정은 없다(기관 값은 센터가 넣는다).
-- 지원서의 이름·생년월일·연락처·주소·학번은 개인정보 수집·이용 동의 뒤에만 저장하고, 탈퇴하면 함께 지운다(ADR-0008).
-- 체험 계정은 데모 묶음(demo_group)으로 나눈다: 같은 묶음의 체험 학생·체험 센터만 서로의 지원서를 보고, 묶음마다
-- 가상 지원자(is_virtual)가 따로 있다. 가입 계정(실제 학생·센터)은 묶음이 없고 체험 묶음을 보지 않는다.

CREATE TABLE round_stage (
    round_id  smallint     NOT NULL REFERENCES recruit_round (id) ON DELETE CASCADE,
    seq       smallint     NOT NULL CHECK (seq BETWEEN 1 AND 20),
    code      varchar(12)  NOT NULL CHECK (code IN ('PICK', 'APPLY', 'MATCH', 'SELECT', 'CONTRACT', 'ORIENTATION',
                                                    'PRACTICE', 'MIDCHECK', 'CLOSE', 'DEBRIEF', 'CREDIT')),
    phase     varchar(10)  NOT NULL CHECK (phase IN ('APPLY', 'PREPARE', 'PRACTICE', 'CLOSE')),
    starts_on date,
    ends_on   date,
    confirmed boolean      NOT NULL,
    source    varchar(150) NOT NULL,
    PRIMARY KEY (round_id, code),
    UNIQUE (round_id, seq),
    CHECK (starts_on IS NULL OR ends_on IS NULL OR starts_on <= ends_on)
);
COMMENT ON TABLE  round_stage IS '회차 일정 11단계(curated/round.json). 진로취업처 학생 모집안내 날짜를 그대로 쓴다';
COMMENT ON COLUMN round_stage.confirmed IS 'true 공지에 있는 날짜 · false 공지에 날짜가 없어 정한 값(중간점검 8주차 등, 센터 확인 전)';

CREATE TABLE demo_group (
    id         uuid        PRIMARY KEY,
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL
);
COMMENT ON TABLE demo_group IS '체험 묶음. 같은 브라우저에서 만든 체험 학생·센터를 잇는다. 마지막 체험 계정과 같이 만료된다';

ALTER TABLE app_user ADD COLUMN demo_group_id uuid REFERENCES demo_group (id) ON DELETE CASCADE;
ALTER TABLE app_user ADD COLUMN demo_today date;
ALTER TABLE app_user ADD CONSTRAINT app_user_demo_guest CHECK (is_guest OR (demo_group_id IS NULL AND demo_today IS NULL));
COMMENT ON COLUMN app_user.demo_today IS '체험 학생의 기준일(지원 중 7/23 · 실습 중 10/14 · 마친 뒤 12/17). 내 현장실습·지원 기간을 이 날로 본다';

CREATE TABLE application (
    id                  bigint        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    round_id            smallint      NOT NULL REFERENCES recruit_round (id),
    user_id             bigint        REFERENCES app_user (id) ON DELETE CASCADE,
    demo_group_id       uuid          REFERENCES demo_group (id) ON DELETE CASCADE,
    is_virtual          boolean       NOT NULL DEFAULT false,
    status              varchar(14)   NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT', 'SUBMITTED', 'RECEIVED', 'FIX_REQUESTED', 'MATCHED')),
    receipt_no          varchar(20),
    -- 신청서 기본정보(동의 뒤에만)
    name_ko             varchar(30),
    name_en             varchar(60),
    birth_date          date,
    gender              char(1)       CHECK (gender IN ('M', 'F')),
    phone               varchar(20),
    email               varchar(254),
    address             varchar(200),
    student_no          varchar(20),
    minor_major         varchar(60),
    -- 학적(낼 때 저장한 프로필에서 옮긴다)
    department_id       smallint      REFERENCES department (id),
    grade               smallint      CHECK (grade BETWEEN 1 AND 4),
    completed_semesters smallint      CHECK (completed_semesters BETWEEN 0 AND 8),
    gpa                 numeric(2, 1) CHECK (gpa BETWEEN 0 AND 4.5),
    graduation_expected boolean,
    -- 이력서·자기소개서·동의·서명
    resume              jsonb         NOT NULL DEFAULT '{"certificates": [], "awards": [], "careers": []}',
    essays              text[]        NOT NULL DEFAULT '{"", "", "", ""}' CHECK (cardinality(essays) = 4),
    pledge              boolean       NOT NULL DEFAULT false,
    consent_collect     boolean       NOT NULL DEFAULT false,
    consent_third_party boolean       NOT NULL DEFAULT false,
    signature           varchar(30),
    counsel_count       smallint      NOT NULL DEFAULT 0 CHECK (counsel_count BETWEEN 0 AND 5),
    -- 센터 처리
    fix_reason          varchar(300),
    matched_rank        smallint      CHECK (matched_rank BETWEEN 1 AND 3),
    matched_at          timestamptz,
    interview_at        timestamptz,
    interview_mode      varchar(10)   CHECK (interview_mode IN ('IN_PERSON', 'ONLINE', 'PHONE')),
    result              varchar(4)    NOT NULL DEFAULT 'WAIT' CHECK (result IN ('WAIT', 'PASS', 'FAIL')),
    result_notified_at  timestamptz,
    created_at          timestamptz   NOT NULL DEFAULT now(),
    updated_at          timestamptz   NOT NULL DEFAULT now(),
    submitted_at        timestamptz,
    received_at         timestamptz,
    CONSTRAINT application_owner   CHECK (is_virtual OR user_id IS NOT NULL),
    CONSTRAINT application_receipt CHECK ((status = 'DRAFT') = (receipt_no IS NULL)),
    CONSTRAINT application_matched CHECK ((status = 'MATCHED') = (matched_at IS NOT NULL)),
    CONSTRAINT application_notice  CHECK (result_notified_at IS NULL OR (status = 'MATCHED' AND result <> 'WAIT'))
);
CREATE UNIQUE INDEX application_user_round ON application (user_id, round_id) WHERE user_id IS NOT NULL;
CREATE UNIQUE INDEX application_receipt_real ON application (round_id, receipt_no)
    WHERE demo_group_id IS NULL AND receipt_no IS NOT NULL;
CREATE UNIQUE INDEX application_receipt_demo ON application (demo_group_id, round_id, receipt_no)
    WHERE demo_group_id IS NOT NULL AND receipt_no IS NOT NULL;
COMMENT ON TABLE  application IS '현장실습 지원서(별지 제5호, 회차당 1부, 1~3지망 공통). 수집·이용 동의 뒤에만 저장, 탈퇴 때 삭제. 사진·계좌는 받지 않는다';
COMMENT ON COLUMN application.is_virtual IS '가상 지원자(체험 묶음) 또는 체험 학생의 지난 기록. 응답에 virtual로 내보낸다';
COMMENT ON COLUMN application.counsel_count IS '진로취업상담 이수 횟수(매칭·선발 가점, 재학 중 최대 5회). 상담 기능 전에는 가상 지원자만 값이 있다';
COMMENT ON COLUMN application.matched_rank IS '센터가 고른 지망(1~3). matched_at은 매칭 확정(학생에게 알림) 시각';
COMMENT ON COLUMN application.result IS '기관이 알려 준 면접·선발 결과를 센터가 넣는다. 학생에게는 result_notified_at 뒤에 보인다';

CREATE TABLE application_pick (
    application_id bigint      NOT NULL REFERENCES application (id) ON DELETE CASCADE,
    rank           smallint    NOT NULL CHECK (rank BETWEEN 1 AND 3),
    job_id         integer     NOT NULL REFERENCES job (id) ON DELETE CASCADE,
    verdict        varchar(12) NOT NULL CHECK (verdict IN ('ELIGIBLE', 'NEEDS_CHECK', 'INELIGIBLE')),
    PRIMARY KEY (application_id, rank),
    UNIQUE (application_id, job_id)
);
COMMENT ON TABLE application_pick IS '낼 때의 1~3지망과 그때의 판정. 내기 전에는 담은 직무 순위(plan_item)를 그대로 보여 준다';

CREATE TABLE dept_approval (
    application_id bigint      NOT NULL REFERENCES application (id) ON DELETE CASCADE,
    kind           varchar(12) NOT NULL CHECK (kind IN ('APPLICATION', 'CREDIT')),
    token          char(32)    NOT NULL UNIQUE,
    content_hash   char(64),
    requested_at   timestamptz NOT NULL DEFAULT now(),
    approved_at    timestamptz,
    PRIMARY KEY (application_id, kind),
    CONSTRAINT dept_approval_hash CHECK ((kind = 'APPLICATION') = (content_hash IS NOT NULL))
);
COMMENT ON TABLE  dept_approval IS '학과(부)장 승인 링크. APPLICATION 지원서(요청할 때의 내용 해시와 같아야 유효) · CREDIT 학점 인정';
COMMENT ON COLUMN dept_approval.token IS '승인 링크의 비밀 값(128비트 무작위). 메일은 보내지 않고 학생·센터가 링크를 전한다';

CREATE TABLE internship_close (
    application_id         bigint      PRIMARY KEY REFERENCES application (id) ON DELETE CASCADE,
    report_submitted_at    timestamptz,
    credit_submitted_at    timestamptz,
    survey_submitted_at    timestamptz,
    evaluation_received_at timestamptz,
    attendance_received_at timestamptz,
    reminded_at            timestamptz
);
COMMENT ON TABLE internship_close IS '마무리 서류. 학생(수행결과보고서 제9호·학점인정신청서 제7호·설문 제8호)과 기관(평가표·출근부, 센터가 받은 것 표시). 파일은 저장하지 않는다';
