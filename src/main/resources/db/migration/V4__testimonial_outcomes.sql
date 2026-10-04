-- V4__testimonial_outcomes.sql — 선배 수기의 실습 결과 중 사실 구절(2026-10-05, ADR-0020)
--
-- 수기는 '우수' 수기만 모은 것이라 긍정 쪽으로 치우쳐 있다. 그래서 기관을 평가하는 데 쓰지 않고 실제 업무 근거로만 쓴다.
-- 실습 결과 문단도 같은 원칙으로, 원문에서 그대로 자른 사실(만든 결과물·맡은 일·참여한 프로젝트·채택된 제안)만 넣는다.
-- 감상·배운 점·평가·개인 진로(입사·채용 등)는 넣지 않는다. 값은 R__seed.sql이 넣는다(pipeline/e2_reviews/extract_outcomes.py).

ALTER TABLE testimonial ADD COLUMN outcomes text[] NOT NULL DEFAULT '{}';

COMMENT ON TABLE  testimonial IS '선배 수기 중 2026-2 참여기관에 연결되는 것만(17건). S3 근거 인용·S4 선배 수기. 이름·사진·학과·학년·소감은 넣지 않는다';
COMMENT ON COLUMN testimonial.outcomes IS '실습 결과 문단에서 원문 그대로 자른 사실 구절 0~3개(60자 이내). 감상·배운 점·평가·개인 진로는 뺀다(ADR-0020)';
