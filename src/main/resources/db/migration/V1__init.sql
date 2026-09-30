-- V1__init.sql — 현장뛰자 온라인 서비스 스키마 (P0 화면: S1~S5, C4)
--
-- 원칙
--   · 시드 테이블은 읽기만 한다. 실행 중에 쓰는 건 계정 3개(app_user·student_profile·plan_item)뿐이다.
--   · 개인정보: 필수 수집은 이메일·비밀번호 해시뿐. 학생 프로필(평점 포함)은 [저장]+동의 때만 저장하고 탈퇴 즉시 삭제한다.
--     요청·응답 로그에 프로필 값을 남기지 않는다. 체험 계정은 24시간 뒤 삭제한다.
--   · 화면에 보이는 값만 시드로 넣는다(ADR-0004). 사업자번호·대표자명·매출액·기타사항·학과×직무 매칭 집계는 넣지 않는다.
--   · 코드값은 영문 대문자, 화면 표기(한글)는 API가 붙인다.
--   · 시드 테이블 PK는 자동 증가 없이 시드가 정한다. 다시 시드해도 /jobs/:id와 담아 둔 직무가 같은 행을 가리켜야 한다.
--     계정 테이블은 실행 중에 행이 생기므로 자동 증가다.
--   · 추천 이유 문장 캐시·IP별 호출 제한은 메모리에 둔다(DB 테이블 없음).
--   · 확장 구조: 사업 → 모집 회차 → 기관 → 직무 → 요건 → 근거. 다른 사업·학교는 행과 판정 규칙만 바꾼다.
--   · 대상 DB: Postgres 18(Render). 쓰는 기능은 Postgres 12 이상이면 모두 있다.
--
-- P1(체크리스트, NCS 직무 풀이)은 V2에서 추가한다.

-- ───────────────────────── 사업·회차 ─────────────────────────

CREATE TABLE program (
    id   smallint     PRIMARY KEY,
    code varchar(30)  NOT NULL UNIQUE,
    name varchar(100) NOT NULL
);
COMMENT ON TABLE  program IS '사업. MVP는 표준 현장실습학기제 1행. 확장 때 취업연계 중점대학·ICT 인턴십 등을 행으로 추가';
COMMENT ON COLUMN program.code IS '판정 규칙 묶음을 고르는 키(예: STANDARD_COOP)';

CREATE TABLE recruit_round (
    id            smallint   PRIMARY KEY,
    program_id    smallint   NOT NULL REFERENCES program(id),
    term_code     varchar(6) NOT NULL,
    round_no      smallint   NOT NULL CHECK (round_no > 0),
    recruit_start date,
    recruit_end   date,
    UNIQUE (program_id, term_code, round_no),
    CONSTRAINT recruit_round_term_format CHECK (term_code ~ '^[0-9]{4}-[12]$'),
    CONSTRAINT recruit_round_period      CHECK (recruit_start IS NULL OR recruit_end IS NULL OR recruit_start <= recruit_end)
);
COMMENT ON TABLE  recruit_round IS '모집 회차. 2026-2는 단일 회차(7/13~7/24)이고 이 기간이 리플레이 날짜 범위다. 지난 회차는 이력용';
COMMENT ON COLUMN recruit_round.term_code IS '학기(예: 2026-2). 한 학기에 1차·2차가 있을 수 있어 round_no로 구분';

-- ───────────────────────── 학과·전공 표기 ─────────────────────────

CREATE TABLE department (
    id             smallint    PRIMARY KEY,
    name           varchar(50) NOT NULL UNIQUE,
    college        varchar(50),
    enrolled_count integer     NOT NULL CHECK (enrolled_count >= 0),
    enrolled_as_of date        NOT NULL
);
COMMENT ON TABLE  department IS '서경대 학과 72개. S1 선택지, C4 적격 학생 풀 계산';
COMMENT ON COLUMN department.enrolled_count IS '재학생 수(교육통계). 학년별 수는 없음';

CREATE TABLE major_alias (
    id    smallint     PRIMARY KEY,
    label varchar(100) NOT NULL UNIQUE
);
COMMENT ON TABLE major_alias IS '공고에 적힌 전공 표기(25종). 사람이 확정한 매핑만 들어간다(M3)';

CREATE TABLE major_alias_department (
    alias_id      smallint NOT NULL REFERENCES major_alias(id) ON DELETE CASCADE,
    department_id smallint NOT NULL REFERENCES department(id),
    PRIMARY KEY (alias_id, department_id)
);
COMMENT ON TABLE major_alias_department IS '전공 표기 1개 → 서경대 학과 N개';

-- ───────────────────────── 기관·직무 ─────────────────────────

CREATE TABLE institution (
    id             integer      PRIMARY KEY,
    name           varchar(100) NOT NULL UNIQUE,
    size           varchar(20)  NOT NULL
        CHECK (size IN ('LARGE', 'MIDSIZE', 'SME', 'PUBLIC', 'ASSOCIATION_ETC', 'UNSPECIFIED')),
    listing        varchar(20)  NOT NULL
        CHECK (listing IN ('KOSPI', 'KOSDAQ', 'UNLISTED', 'UNSPECIFIED')),
    business_type  varchar(100),
    business_item  varchar(200),
    address        varchar(200),
    nts_status     varchar(20) CHECK (nts_status IN ('ACTIVE', 'SUSPENDED', 'CLOSED')),
    nts_checked_on date,
    CONSTRAINT institution_nts_pair CHECK ((nts_status IS NULL) = (nts_checked_on IS NULL))
);
COMMENT ON TABLE  institution IS '실습기관. 회차를 넘어 유지된다(지난 학기 수기·이력이 같은 기관을 가리킴)';
COMMENT ON COLUMN institution.business_type IS '업태';
COMMENT ON COLUMN institution.business_item IS '종목';
COMMENT ON COLUMN institution.nts_status IS '국세청 사업자 상태. 사업자번호는 저장하지 않고 조회 결과만 둔다';

CREATE TABLE workplace (
    id                integer      PRIMARY KEY,
    institution_id    integer      NOT NULL REFERENCES institution(id) ON DELETE CASCADE,
    address           varchar(200) NOT NULL,
    commute_minutes   smallint CHECK (commute_minutes > 0),
    commute_transfers smallint CHECK (commute_transfers >= 0),
    UNIQUE (institution_id, address),
    UNIQUE (id, institution_id)
);
COMMENT ON TABLE  workplace IS '근로지. 서경대 기준 대중교통 소요시간은 ODsay로 미리 계산(ADR-0002). 좌표는 오프라인에만 둔다';

CREATE TABLE job (
    id                   integer      PRIMARY KEY,
    round_id             smallint     NOT NULL REFERENCES recruit_round(id),
    institution_id       integer      NOT NULL REFERENCES institution(id) ON DELETE CASCADE,
    workplace_id         integer,
    list_seq             smallint     NOT NULL CHECK (list_seq > 0),
    team                 varchar(100) NOT NULL,
    title                varchar(200) NOT NULL,
    overview             text,
    education_goal       text,
    competencies         text,

    course               varchar(20)  NOT NULL
        CHECK (course IN ('VACATION', 'SEMESTER', 'VACATION_SEMESTER', 'UNSPECIFIED')),
    job_type             varchar(20)  NOT NULL
        CHECK (job_type IN ('EXPERIENCE', 'HIRING', 'UNSPECIFIED')),
    period_start         date,
    period_end           date,
    work_hours_text      varchar(100),
    weekly_hours         numeric(4, 1) CHECK (weekly_hours > 0),
    weekdays             varchar(3)[] NOT NULL DEFAULT '{}'
        CHECK (weekdays <@ ARRAY['MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT', 'SUN']::varchar(3)[]),
    overtime             varchar(20)  NOT NULL
        CHECK (overtime IN ('NONE', 'OCCASIONAL', 'REGULAR', 'UNSPECIFIED')),
    labor_contract       boolean,
    stipend_basis        varchar(20)  NOT NULL
        CHECK (stipend_basis IN ('MONTHLY', 'HOURLY', 'UNSPECIFIED')),
    stipend_amount       integer CHECK (stipend_amount > 0),
    benefits             varchar(20)[] NOT NULL DEFAULT '{}'
        CHECK (benefits <@ ARRAY['MEAL', 'TRANSPORT', 'DORM', 'IN_KIND']::varchar(20)[]),

    headcount            smallint     NOT NULL CHECK (headcount > 0),
    grade_rule           varchar(20)  NOT NULL CHECK (grade_rule IN ('Y3_4', 'Y4', 'GRADUATING')),
    gpa_min              numeric(2, 1) CHECK (gpa_min BETWEEN 0 AND 4.5),
    portfolio            varchar(20)  NOT NULL CHECK (portfolio   IN ('REQUIRED', 'PREFERRED', 'NONE')),
    certificate          varchar(20)  NOT NULL CHECK (certificate IN ('REQUIRED', 'PREFERRED', 'NONE')),
    certificate_text     varchar(200),
    major_text           varchar(300),
    major_open           boolean      NOT NULL DEFAULT false,

    closes_on            date,
    close_reason         varchar(30)
        CHECK (close_reason IN ('APPLICATION_DEADLINE', 'CENTER_CLOSED')),
    closes_on_is_virtual boolean      NOT NULL DEFAULT false,
    final_assigned       smallint CHECK (final_assigned >= 0),

    UNIQUE (round_id, list_seq),
    UNIQUE (id, institution_id),
    FOREIGN KEY (workplace_id, institution_id) REFERENCES workplace(id, institution_id),
    CONSTRAINT job_period_order CHECK (period_start IS NULL OR period_end IS NULL OR period_start <= period_end),
    CONSTRAINT job_close_pair   CHECK ((closes_on IS NULL) = (close_reason IS NULL)),
    CONSTRAINT job_close_virtual CHECK (NOT closes_on_is_virtual OR close_reason = 'CENTER_CLOSED')
);
COMMENT ON TABLE  job IS '직무. 1행 = 팀 1개(E1 확인). 2026-2는 18기관 40직무, 정원 합 59';
COMMENT ON COLUMN job.list_seq IS '센터 참여기관 리스트 순번(원본 대조용)';
COMMENT ON COLUMN job.team IS '부서(팀). 추출 스키마의 department. 학과(department 테이블)와 구분하려고 이름을 바꿈';
COMMENT ON COLUMN job.stipend_amount IS '원. stipend_basis가 MONTHLY면 월액, HOURLY면 시급. 최저임금 대비 %는 API가 계산';
COMMENT ON COLUMN job.grade_rule IS 'Y3_4=3·4학년 / Y4=4학년 / GRADUATING=졸업예정자(프로필 체크박스로 판정)';
COMMENT ON COLUMN job.gpa_min IS '학점 하한(3.0/3.3/3.5/3.6). 없으면 NULL';
COMMENT ON COLUMN job.major_text IS '선호 전공 원문(화면 표시용). 판정은 job_major_alias로';
COMMENT ON COLUMN job.major_open IS '전공 무관이면 true(적격 풀 = 전체 재학생)';
COMMENT ON COLUMN job.closes_on IS '이 날부터 지원 불가(모집마감). 회차 종료일보다 이를 때만 넣는다. NULL이면 회차 끝까지 열림';
COMMENT ON COLUMN job.close_reason IS 'APPLICATION_DEADLINE=운영계획서 접수마감일자 / CENTER_CLOSED=센터 리스트의 모집마감 표시. 둘 다 있으면 이른 날짜';
COMMENT ON COLUMN job.closes_on_is_virtual IS 'CENTER_CLOSED인데 실제 마감 날짜 기록이 없어 리플레이 생성기가 정한 날짜면 true(화면에 가상 표시)';
COMMENT ON COLUMN job.final_assigned IS '최종 배정 수(직무별 수만. 학과별 집계는 넣지 않음)';

CREATE TABLE job_major_alias (
    job_id   integer  NOT NULL REFERENCES job(id) ON DELETE CASCADE,
    alias_id smallint NOT NULL REFERENCES major_alias(id),
    PRIMARY KEY (job_id, alias_id)
);
COMMENT ON TABLE job_major_alias IS '직무의 선호 전공 표기. S2 3층(참고 표시)과 C4 적격 풀 계산에 쓴다';

CREATE TABLE job_weekly_plan (
    job_id      integer     NOT NULL REFERENCES job(id) ON DELETE CASCADE,
    seq         smallint    NOT NULL CHECK (seq > 0),
    weeks_label varchar(30) NOT NULL,
    content     text        NOT NULL,
    PRIMARY KEY (job_id, seq)
);
COMMENT ON TABLE job_weekly_plan IS '운영/지도 계획의 주차별 항목(S4). 임베딩 입력에도 쓴다';

-- ───────────────────────── 근거·검토 ─────────────────────────

CREATE TABLE source_document (
    id             integer      PRIMARY KEY,
    kind           varchar(20)  NOT NULL CHECK (kind IN ('OPERATION_PLAN', 'TESTIMONIAL')),
    title          varchar(200) NOT NULL UNIQUE,
    term_code      varchar(6)   NOT NULL CHECK (term_code ~ '^[0-9]{4}-[12]$'),
    institution_id integer REFERENCES institution(id) ON DELETE CASCADE,
    page_count     smallint     NOT NULL CHECK (page_count > 0),
    CONSTRAINT source_document_plan_institution CHECK ((kind = 'OPERATION_PLAN') = (institution_id IS NOT NULL))
);
COMMENT ON TABLE  source_document IS '근거 문서 목록. 원본 파일은 저장소·공개 URL에 두지 않고 문서명·쪽수만 둔다';
COMMENT ON COLUMN source_document.title IS '화면 표기 문서명(예: 선도소프트 운영계획서, 2025-1 우수 참여수기)';

CREATE TABLE field_evidence (
    id                 integer      PRIMARY KEY,
    source_document_id integer      NOT NULL REFERENCES source_document(id) ON DELETE CASCADE,
    institution_id     integer REFERENCES institution(id) ON DELETE CASCADE,
    job_id             integer REFERENCES job(id) ON DELETE CASCADE,
    field_key          varchar(40)  NOT NULL,
    raw_value          text         NOT NULL,
    page               smallint     NOT NULL CHECK (page > 0),
    quote              varchar(200) NOT NULL,
    CONSTRAINT field_evidence_one_subject CHECK (num_nonnulls(institution_id, job_id) = 1),
    CONSTRAINT field_evidence_allowed_key CHECK (
        (institution_id IS NOT NULL AND field_key IN (
            'name', 'size', 'listing', 'businessType', 'businessItem', 'address', 'applicationDeadline'))
        OR
        (job_id IS NOT NULL AND field_key IN (
            'department', 'jobTitle', 'workAddress', 'course', 'jobType', 'period', 'hours', 'weekdays',
            'overtime', 'laborContract', 'stipendBasis', 'stipendAmount', 'benefits', 'educationGoal',
            'jobOverview', 'majorRequirement', 'headcount', 'gradeRequirement', 'gpaRequirement',
            'competencies', 'portfolio', 'certificate'))
    )
);
CREATE UNIQUE INDEX field_evidence_institution_key ON field_evidence (institution_id, field_key) WHERE institution_id IS NOT NULL;
CREATE UNIQUE INDEX field_evidence_job_key         ON field_evidence (job_id, field_key)         WHERE job_id IS NOT NULL;
COMMENT ON TABLE  field_evidence IS 'S4 ''AI가 뽑은 값 ↔ 근거''. 추출 필드 1개 = 1행. 근거가 없는 필드(page 0)는 행을 만들지 않는다';
COMMENT ON COLUMN field_evidence.field_key IS '추출 레코드 필드명 그대로(camelCase). 허용 목록 밖(businessNo·revenue·notes 등)은 DB가 거부';
COMMENT ON COLUMN field_evidence.raw_value IS '문서에 적힌 그대로. 정규화 값은 institution/job 컬럼';

CREATE TABLE review_alert (
    id                 integer      PRIMARY KEY,
    institution_id     integer      NOT NULL REFERENCES institution(id) ON DELETE CASCADE,
    job_id             integer,
    kind               varchar(20)  NOT NULL CHECK (kind IN ('DOC_INCONSISTENCY', 'RULE_CHECK', 'LIST_MISMATCH')),
    field_key          varchar(40),
    description        text         NOT NULL,
    source_document_id integer REFERENCES source_document(id) ON DELETE CASCADE,
    page_a             smallint CHECK (page_a > 0),
    quote_a            varchar(200),
    page_b             smallint CHECK (page_b > 0),
    quote_b            varchar(200),
    FOREIGN KEY (job_id, institution_id) REFERENCES job(id, institution_id) ON DELETE CASCADE
);
COMMENT ON TABLE  review_alert IS 'M2 결과. 문서 내부 불일치 / 규정 점검(최저임금 75%·주 40시간 등) / 리스트↔계획서 불일치. C4 검토 알림';
COMMENT ON COLUMN review_alert.field_key IS '판정 항목(majorRequirement 등)에 걸리면 S2에서 그 항목을 ''확인 필요''로 바꾼다';

CREATE TABLE testimonial (
    id                 integer      PRIMARY KEY,
    source_document_id integer      NOT NULL REFERENCES source_document(id) ON DELETE CASCADE,
    institution_id     integer      NOT NULL REFERENCES institution(id) ON DELETE CASCADE,
    team_text          varchar(100),
    activities         text[]       NOT NULL CHECK (cardinality(activities) > 0),
    page               smallint     NOT NULL CHECK (page > 0)
);
COMMENT ON TABLE  testimonial IS '선배 수기 중 2026-2 참여기관에 연결되는 것만(17건). S3 근거 인용. 이름·사진·학과·학년은 넣지 않는다';
COMMENT ON COLUMN testimonial.page IS '원본 PDF 기준 쪽(분할 추출했다면 보정한 값)';

-- ───────────────────────── 추천·리플레이·이력 ─────────────────────────

CREATE TABLE job_embedding (
    job_id      integer     PRIMARY KEY REFERENCES job(id) ON DELETE CASCADE,
    model       varchar(50) NOT NULL,
    vector      real[]      NOT NULL CHECK (array_ndims(vector) = 1 AND cardinality(vector) > 0),
    source_hash char(64)    NOT NULL
);
COMMENT ON TABLE  job_embedding IS '직무 텍스트 임베딩(오프라인 계산). 실행 중에는 메모리에 올려 코사인 유사도. E5 불합격이면 쓰지 않음';
COMMENT ON COLUMN job_embedding.source_hash IS '임베딩한 텍스트의 SHA-256. 직무 텍스트가 바뀌면 다시 계산';

CREATE TABLE replay_signal (
    job_id         integer  NOT NULL REFERENCES job(id) ON DELETE CASCADE,
    signal_date    date     NOT NULL,
    interest_count smallint NOT NULL CHECK (interest_count >= 0),
    intent_count   smallint NOT NULL CHECK (intent_count >= 0),
    PRIMARY KEY (job_id, signal_date)
);
COMMENT ON TABLE  replay_signal IS '모집기간 리플레이용 가상 신호(생성기 시드 고정). 그날 새로 생긴 수이고 누적이 아니다. API가 asOf까지 합산. 마감(closes_on) 뒤에는 행을 만들지 않는다';

CREATE TABLE round_result (
    institution_id integer  NOT NULL REFERENCES institution(id) ON DELETE CASCADE,
    round_id       smallint NOT NULL REFERENCES recruit_round(id),
    seats          smallint NOT NULL CHECK (seats > 0),
    matched        smallint NOT NULL CHECK (matched >= 0),
    PRIMARY KEY (institution_id, round_id)
);
COMMENT ON TABLE round_result IS '지난 회차 기관별 모집석·배정 수. C4 ''지난 학기 0명 이력''. 과거 학기 기관별 매칭 수라 저장소 시드에 넣지 않고, 센터 동의 뒤 로컬 시드로만 적재한다. 비어 있으면 C4는 이 칸을 숨긴다';

-- ───────────────────────── 계정 ─────────────────────────

CREATE TABLE app_user (
    id            bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email         varchar(254),
    password_hash varchar(100),
    role          varchar(10)  NOT NULL CHECK (role IN ('STUDENT', 'CENTER')),
    is_guest      boolean      NOT NULL DEFAULT false,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    expires_at    timestamptz,
    CONSTRAINT app_user_guest_shape CHECK (
        (is_guest AND email IS NULL AND password_hash IS NULL AND expires_at IS NOT NULL)
        OR
        (NOT is_guest AND email IS NOT NULL AND password_hash IS NOT NULL AND expires_at IS NULL))
);
CREATE UNIQUE INDEX app_user_email_key ON app_user (lower(email)) WHERE email IS NOT NULL;
COMMENT ON TABLE  app_user IS '계정. 필수 수집은 이메일·비밀번호 해시뿐. 체험 계정(is_guest)은 [예시 프로필로 시작]·[센터 담당자로 보기]가 만들고 24시간 뒤 삭제';
COMMENT ON COLUMN app_user.role IS 'CENTER는 가입으로 만들 수 없다(시드·관리자만). C4 현황판 권한';

CREATE TABLE student_profile (
    user_id             bigint       PRIMARY KEY REFERENCES app_user(id) ON DELETE CASCADE,
    department_id       smallint     NOT NULL REFERENCES department(id),
    grade               smallint     NOT NULL CHECK (grade BETWEEN 1 AND 4),
    completed_semesters smallint     NOT NULL CHECK (completed_semesters BETWEEN 0 AND 8),
    gpa                 numeric(2, 1) NOT NULL CHECK (gpa BETWEEN 0 AND 4.5),
    graduation_expected boolean      NOT NULL DEFAULT false,
    interest_text       varchar(200),
    consented_at        timestamptz  NOT NULL,
    updated_at          timestamptz  NOT NULL DEFAULT now()
);
COMMENT ON TABLE  student_profile IS '학생이 [저장]을 누르고 동의했을 때만 생긴다. 저장 안 하면 판정 요청 본문으로만 쓰고 버린다. 탈퇴 시 즉시 삭제';
COMMENT ON COLUMN student_profile.graduation_expected IS '현재 회차 기준 다음 졸업 예정(2026-2 → 2027년 2월). 졸업예정자 요건 판정';
COMMENT ON COLUMN student_profile.consented_at IS '수집 동의 시각(항목·목적·보관기간 고지 후)';

CREATE TABLE plan_item (
    user_id  bigint      NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    job_id   integer     NOT NULL REFERENCES job(id) ON DELETE CASCADE,
    rank     smallint    CHECK (rank BETWEEN 1 AND 3),
    added_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, job_id)
);
CREATE UNIQUE INDEX plan_item_rank_key ON plan_item (user_id, rank) WHERE rank IS NOT NULL;
COMMENT ON TABLE  plan_item IS 'S3에서 담은 직무. rank NULL = 담기만, 1~3 = 지망 순위(S5). 운영 단계에서는 체험 계정을 뺀 1인 1표 집계가 실제 관심·지원 신호가 된다';
