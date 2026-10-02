-- V2__no_stored_coordinates.sql — 좌표를 DB에 두지 않는다(2026-10-02, ADR-0007 개정)
--
-- 통근 조회를 카카오만으로 한다: 조회할 때마다 카카오 주소 검색으로 출발(사는 곳 시·군·구)·도착(근로지 주소) 좌표를 구하고
-- 카카오 대중교통 길찾기를 부른 뒤 버린다. 카카오 운영정책상 API 결과(좌표 포함)는 DB·캐시·시드에 저장할 수 없으므로
-- 좌표 칸을 없앤다. 행정안전부 도로명주소 좌표(V1 계획)는 쓰지 않는다.
-- V1의 '출발점(area)과 도착점(workplace) 좌표만 둔다' 주석은 이 파일로 바뀐다(적용한 마이그레이션은 고치지 않는다, ADR-0010).

ALTER TABLE workplace DROP CONSTRAINT workplace_coord_shape;
ALTER TABLE workplace
    DROP COLUMN lat,
    DROP COLUMN lng,
    DROP COLUMN coord_source;
COMMENT ON TABLE workplace IS '근로지. 주소는 통근 조회(ADR-0007)의 도착점 — 조회할 때마다 카카오 주소 검색으로 좌표를 구하고 버린다';

ALTER TABLE area
    DROP COLUMN lat,
    DROP COLUMN lng;
COMMENT ON TABLE area IS '사는 곳 선택지(서울·인천·경기 시·군·구, 시드). 통근 조회 출발점 — 조회할 때마다 카카오 주소 검색으로 좌표를 구한다. 정확한 주소는 받지 않는다';
