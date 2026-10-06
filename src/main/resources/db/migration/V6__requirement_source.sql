-- V6__requirement_source.sql — 판정 이유 줄의 출처(2026-10-07, ADR-0023)
--
-- 판정이 쓴 요건 값이 어느 문서의 어느 글에서 왔는지 둔다. 학년·학점·선호 전공·포트폴리오는 센터 참여기관 리스트 칸 원문
-- (쪽이 없다), 자격증과 리스트에 없는 요건은 운영계획서 쪽·인용이다. 학교 규정의 출처(학생 모집안내)는 규칙과 함께 코드에 둔다.
-- 값은 R__seed.sql이 넣는다(pipeline/seed/build_seed.py).

CREATE TABLE requirement_source (
    job_id          integer      NOT NULL REFERENCES job (id) ON DELETE CASCADE,
    item            varchar(20)  NOT NULL CHECK (item IN ('GRADE', 'GPA', 'MAJOR', 'PORTFOLIO', 'CERTIFICATE')),
    source_type     varchar(20)  NOT NULL CHECK (source_type IN ('INSTITUTION_LIST', 'OPERATION_PLAN')),
    document_title  varchar(200) NOT NULL,
    page            smallint     CHECK (page > 0),
    quote           varchar(300) NOT NULL,
    PRIMARY KEY (job_id, item),
    CONSTRAINT requirement_source_page CHECK ((source_type = 'OPERATION_PLAN') = (page IS NOT NULL))
);

COMMENT ON TABLE  requirement_source IS '판정 이유 줄의 출처(ADR-0023). 판정이 쓴 요건 값의 원문. 원문 PDF·파일은 주지 않는다';
COMMENT ON COLUMN requirement_source.item IS 'GRADE 학년 · GPA 학점 · MAJOR 선호 전공 · PORTFOLIO 포트폴리오 · CERTIFICATE 자격증';
COMMENT ON COLUMN requirement_source.page IS '운영계획서 쪽. 참여기관 리스트(엑셀)는 쪽이 없어 NULL';
COMMENT ON COLUMN requirement_source.quote IS '원문 그대로(앞의 ■·- 글머리표만 뗌)';
