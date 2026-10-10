-- V12 쓰지 않는 테이블 3개를 지운다(ADR-0036, 2026-10-10 백엔드 설계 재검토 A·B·F)
-- replay_signal: 모집기간 리플레이 가상 관심(모집 신호 #23·#27·현황판 관심 열과 함께 걷어냄, ADR-0015·0019·0029를 대체)
-- job_embedding: E5에서 임베딩을 쓰지 않기로 해 비어 있음(ADR-0018)
-- round_result: 지난 회차 결과(센터 동의 전이라 비어 있음). 다시 필요하면 그때 모양을 정해 새 마이그레이션으로 만든다
DROP TABLE replay_signal;
DROP TABLE job_embedding;
DROP TABLE round_result;
