-- V8__intro_photos_and_testimonial_text.sql — 소개서 사진과 선배 수기 전문(2026-10-10, ADR-0030)
--
-- 1) 실습기관 소개서(별지 제1-2호)의 '회사 전경 및 활동사진' 칸 사진을 직무 상세에 보여 준다. 그림 파일은
--    static/photos/{기관 id}/{순번}.jpg에 두고(로고와 같은 방식, ADR-0019) 여기에는 순번·쪽·캡션만 둔다.
--    소개서도 근거 문서(source_document)로 둔다 — 종류 INTRODUCTION, 기관 문서라 institution_id가 있다.
-- 2) 선배 수기는 실습 내용·결과 구절만 두던 것(ADR-0020)을 전문(한 줄 소개·회사 소개·실습 결과·소감)과 학과·학년까지
--    넣는다(10/10 결정). '우수' 수기라 긍정 쪽으로 치우쳐 있다는 표시는 화면이 한다. 이름·사진은 여전히 넣지 않는다.
-- 값은 R__seed.sql이 넣는다(pipeline/seed/build_seed.py, pipeline/e8_intro_photos).

ALTER TABLE source_document DROP CONSTRAINT source_document_kind_check;
ALTER TABLE source_document ADD CONSTRAINT source_document_kind_check
    CHECK (kind IN ('OPERATION_PLAN', 'TESTIMONIAL', 'INTRODUCTION'));
ALTER TABLE source_document DROP CONSTRAINT source_document_plan_institution;
ALTER TABLE source_document ADD CONSTRAINT source_document_plan_institution
    CHECK ((kind IN ('OPERATION_PLAN', 'INTRODUCTION')) = (institution_id IS NOT NULL));
COMMENT ON COLUMN source_document.title IS '화면 표기 문서명(예: 선도소프트 운영계획서, 2025-1 우수 참여수기, 소서 실습기관 소개서)';

CREATE TABLE institution_photo (
    id                 integer      PRIMARY KEY,
    institution_id     integer      NOT NULL REFERENCES institution (id) ON DELETE CASCADE,
    source_document_id integer      NOT NULL REFERENCES source_document (id) ON DELETE CASCADE,
    seq                smallint     NOT NULL CHECK (seq > 0),
    page               smallint     NOT NULL CHECK (page > 0),
    scene              varchar(20)  NOT NULL CHECK (scene IN ('OFFICE', 'EVENT', 'BUILDING', 'PRODUCT', 'PERSON', 'OTHER')),
    caption            varchar(100),
    caption_source     varchar(10)  CHECK (caption_source IN ('TEXT', 'VISION', 'MANUAL')),
    width              smallint     NOT NULL CHECK (width > 0),
    height             smallint     NOT NULL CHECK (height > 0),
    UNIQUE (institution_id, seq),
    CONSTRAINT institution_photo_caption CHECK ((caption IS NULL) = (caption_source IS NULL))
);
COMMENT ON TABLE  institution_photo IS '실습기관 소개서의 회사 전경·활동 사진(ADR-0030). 파일은 static/photos/{institution_id}/{seq}.jpg';
COMMENT ON COLUMN institution_photo.scene IS 'OFFICE 근무환경 · EVENT 활동·행사 · BUILDING 건물·전경 · PRODUCT 제품 · PERSON 인물 · OTHER 기타(AI 분류)';
COMMENT ON COLUMN institution_photo.caption IS '사진 아래에 인쇄된 설명 원문. 없으면 NULL';
COMMENT ON COLUMN institution_photo.caption_source IS 'TEXT PDF 글자 층과 같음 · VISION 글자 층이 없어 AI가 읽음 · MANUAL 사람이 적음';

ALTER TABLE testimonial
    ADD COLUMN one_line      text,
    ADD COLUMN company_intro text,
    ADD COLUMN results       text,
    ADD COLUMN reflection    text,
    ADD COLUMN major_text    varchar(100),
    ADD COLUMN grade_text    varchar(20);
COMMENT ON TABLE  testimonial IS '선배 수기 중 2026-2 참여기관에 연결되는 것만(17건). 전문과 학과·학년(ADR-0030). 이름·사진은 넣지 않는다';
COMMENT ON COLUMN testimonial.one_line IS '한 줄 소개(제목)';
COMMENT ON COLUMN testimonial.company_intro IS '수기의 기관·부서 소개 문단';
COMMENT ON COLUMN testimonial.results IS '실습 결과 문단 전문(원문을 AI로 옮겨 적은 것)';
COMMENT ON COLUMN testimonial.reflection IS '소감 문단 전문';
COMMENT ON COLUMN testimonial.major_text IS '수기에 적힌 학과(전공) 원문';
COMMENT ON COLUMN testimonial.grade_text IS '수기에 적힌 학년 원문';
