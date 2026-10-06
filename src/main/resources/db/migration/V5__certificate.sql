-- V5__certificate.sql — 자격증 판정(2026-10-07, ADR-0021)
--
-- 운영계획서의 자격증 요건을 코드로 두고, 학생 프로필에 가진 자격증을 받는다.
-- 직무가 필수로 요구하는 자격증이 프로필에 없으면 판정이 '지원 불가'가 된다(기관 조건 중 유일).
-- 코드표와 직무의 코드는 R__seed.sql이 넣는다(pipeline/seed/curated/certificates.csv · overrides.json).

CREATE TABLE certificate (
    code        varchar(40)  PRIMARY KEY CHECK (code ~ '^[A-Z][A-Z0-9_]*$'),
    label       varchar(100) NOT NULL,
    sort_order  integer      NOT NULL
);

ALTER TABLE job ADD COLUMN certificate_code varchar(40) REFERENCES certificate (code);
ALTER TABLE job ADD CONSTRAINT job_certificate_code CHECK ((certificate = 'NONE') = (certificate_code IS NULL));

ALTER TABLE student_profile ADD COLUMN certificates varchar(40)[];
ALTER TABLE student_profile ADD CONSTRAINT student_profile_certificates_size
    CHECK (certificates IS NULL OR cardinality(certificates) <= 20);

COMMENT ON TABLE  certificate IS '판정에 쓰는 자격증 코드표. 회차 직무가 요구·우대하는 것만 둔다(최소 수집, ADR-0021). 프로필이 쓰는 코드는 시드에서 빠져도 지우지 않는다';
COMMENT ON COLUMN job.certificate_code IS '자격증 요건(certificate)이 필수·우대일 때 그 자격증 코드. 언급 없음(NONE)이면 NULL';
COMMENT ON COLUMN student_profile.certificates IS '가진 자격증 코드. NULL = 답하지 않음(자격증 줄이 직접 확인), 빈 배열 = 없음. 판정의 자격증 줄에만 쓴다';
