-- V7__department_cluster.sql — 가까운 학과 묶음(2026-10-07, ADR-0028)
--
-- 선호 전공에 내 학과가 없어도 같은 분야 학과(예: 컴퓨터공학과 ↔ 소프트웨어학과)면 '가까운 전공'으로 본다.
-- 판정(지원 가능 여부)은 바꾸지 않고, 선호 전공 표시·적합도·이유 문장에만 쓴다.
-- 행은 R__seed.sql이 넣는다(pipeline/seed/curated/department_clusters.csv의 CONFIRMED만).

CREATE TABLE department_cluster (
    id    smallint    PRIMARY KEY,
    label varchar(50) NOT NULL UNIQUE
);

CREATE TABLE department_cluster_member (
    cluster_id    smallint NOT NULL REFERENCES department_cluster(id) ON DELETE CASCADE,
    department_id smallint NOT NULL REFERENCES department(id),
    PRIMARY KEY (cluster_id, department_id)
);

COMMENT ON TABLE department_cluster IS '가까운 학과 묶음(사람이 확정한 것만). 같은 묶음의 학과끼리 가까운 전공(ADR-0028)';
COMMENT ON TABLE department_cluster_member IS '묶음 1개 → 학과 N개. 한 학과가 여러 묶음에 들 수 있다';
