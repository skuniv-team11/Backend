# pipeline/AGENTS.md

서비스가 실행 중에 부르지 않는 작업을 로컬에서 돌리고, 검수된 결과만 시드로 넘긴다. Python 3.10+.

## 명령
```
pip install -r requirements.txt
python ../scripts/check_pipeline.py   # 문법 + 스키마 제약 검사 — 통과해야 끝
```
실험별 실행법은 각 폴더 README(e1·e2·e5·e6).

## 규칙
- **원본은 저장소 밖**에 둔다. 스크립트는 경로를 인자로 받는다. 결과물(`out/`, `review_*.csv`, `*_report.md`, E6 CSV)은 커밋하지 않는다(.gitignore).
- **Claude structured outputs 스키마**: 모든 object에 `additionalProperties: false`, 모든 속성 required, `anyOf`·`["string","null"]`·`minLength`·`pattern`·`minimum` 금지. 값이 없으면 ""/0. → `check_pipeline.py`가 막는다(ADR-0003).
- 스키마는 `build_schema.py`를 고치고 다시 생성한다. `schema*.json`을 손으로 고치지 않는다.
- **숫자·날짜 정규화는 코드**가 한다. 모델에게는 원문 그대로 받는다.
- 외부 API 키는 환경변수로만 받는다. 공공데이터포털은 **Decoding 키**(requests가 한 번 인코딩).
- 시드로 넘길 때는 화면에 보이는 필드만 남긴다(사업자번호·대표자명·학과×직무 집계 제외).

## 시드 (`seed/`, ADR-0014)
- `seed.json` → `to_sql.py` → `src/main/resources/db/migration/R__seed.sql`. **SQL은 손으로 고치지 않는다**(`to_sql.py --check`, `SeedFileTest`가 막는다).
- 시드 id는 `seed/curated/ids.json`에서만 정한다. 이미 있는 id는 바꾸지 않는다(담아 둔 지망이 같은 직무를 가리켜야 한다).
- 전공 표기 → 학과 연결은 사람이 확정한 것(`EXACT`·`CONFIRMED`)만 적재한다. 초안은 `DRAFT`.
- 매칭 결과 xlsx는 `matching_counts.py`로만 읽는다('지원 기관명'·'지원 직무' 두 열, 직무별 수만).

## 공용
- `common/jobs_sheet.py`: 참여기관 리스트 xlsx 파서(병합 셀 채움), 기관명 표준화(`canon`: 원오세븐→소서, 세정 OL디자인팀→오뷔엘알 등)
