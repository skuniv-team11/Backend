# seed — 시드 만들기 (ADR-0014)

원본 자료와 추출 결과를 합쳐 `seed.json`을 만들고, 그걸 `src/main/resources/db/migration/R__seed.sql`(Flyway 반복 마이그레이션)로 바꾼다. 앱이 뜰 때 Flyway가 `V*` 다음에 적용한다.

```
원본(저장소 밖)                         추출·조회 결과(.gitignore)            사람이 확인한 입력(커밋)
참여기관 리스트 xlsx ─┐                 e1 out/full/*.json (운영계획서) ─┐    curated/round.json        사업·회차
매칭 결과 xlsx ── matching_counts.py ─→ out/final_assigned.csv           ├─→ curated/departments.csv   학과(교육통계)
                      │                 e2 out/*.json (수기)             │    curated/major_aliases.csv 전공 표기 → 학과
                      │                 e6 nts_status.csv (국세청)       │    curated/overrides.json    화면용 고침
                      └──────────────── build_seed.py ←──────────────────┘    curated/ids.json          시드 id 등록부
                                             │
                               seed.json(커밋) + out/review_seed.csv(사람 검토용)
                                             │
                                        to_sql.py → R__seed.sql(커밋)
```

## 다시 만들기

```
cd pipeline/seed
# 1) 직무별 배정 수. '지원 기관명'·'지원 직무' 두 열만 읽는다(이름·학과·연락처는 읽지 않음)
python matching_counts.py "<원본>/★ 2026학년도 2학기 ... 기관-학생 매칭 결과.xlsx"

# 2) 시드
python build_seed.py --list "<원본>/2026학년도 2학기 ... 참여기관 리스트(...).xlsx" \
  --plans ../e1_operation_plan/out/full --reviews ../e2_reviews/out \
  --nts ../e6_external/nts_status.csv --nts-checked-on 2026-10-01 \
  --assigned out/final_assigned.csv --pages out/pages.csv

# 3) SQL
python to_sql.py
```

- `--pages`: 원본 PDF 쪽수 CSV(`file,pages`). 운영계획서 추출 JSON에 `source_pages`가 있으면(10/1 이후 `run_extract.py`) 필요 없다. 수기 PDF는 이 CSV로 준다.
- 끝나면 `out/review_seed.csv`를 열어 직무 40행을 훑는다(기관·부서·직무명·정원·배정·학년·학점·선호 전공·마감).
- `seed.json`·`R__seed.sql`·`curated/ids.json`을 같이 커밋한다. `scripts/check_pipeline.py`가 단위 테스트와 `to_sql.py --check`(SQL이 seed.json에서 만든 그대로인지)를 돌리고, 백엔드 `SeedFileTest`가 SQL 머리글의 해시를, `SeedTest`가 적용 결과(18기관·40직무·정원 59·배정 21·수기 17, 리플레이 규칙)를 본다.

## 값을 어디서 가져오나

같은 항목이 두 문서에 있으면 아래 기준을 쓰고, 값이 다르면 `review_alert`(`LIST_MISMATCH`)를 만든다. 계획서 값이 '선호'·'무관'·'무방'처럼 확실하지 않으면 비교하지 않는다.

| 기준 | 항목 |
|---|---|
| 참여기관 리스트(센터가 정리·공지) | 정원, 실습지원비, 실습기간, 요일, 근무시간, 학년, 학점, 선호 전공(표기 그대로), 근로지 주소, 모집마감 표시, 기관 이름(법인 형태 표기만 뺌) |
| 운영계획서 추출 | 기관 현황(규모·상장·업태·종목·주소), 부서·직무명, 직무 개요·교육목표·요구역량, 주차별 계획, 과정·유형, 연장실습, 근로계약, 지급 기준, 복리(리스트의 '식사 지원'도 더함), 자격증, 접수마감일, 근거(쪽·인용문), 문서 내부 불일치(`DOC_INCONSISTENCY`) |
| 규칙(`RULE_CHECK`) | 실습지원비 < 최저임금 75%, 주 40시간 초과. 2026-2는 해당 없음 |

- **마감(`closes_on` = 이 날부터 지원 불가)**: 접수마감이 그날 안의 시각(18시·24시·시각 없음)이면 다음 날, '0시'면 그날. 계획서 접수마감(`APPLICATION_DEADLINE`)과 리스트 모집마감 표시의 서류마감일(`CENTER_CLOSED`) 중 이른 날, 회차 종료일보다 이를 때만. 리스트에 모집마감만 있고 날짜가 없으면 `replay.py`가 날짜를 정하고 `closes_on_is_virtual = true`(2026-2는 없음).
- **학과**: 교육통계 2025-10-01 학과별 재학생(72행) 중 재학생이 있는 60개. 폐지·통합 단위(재학생 0)는 선택지에서 뺀다. id는 교육통계 파일의 행 순서로 고정했다.
- **전공 표기 → 학과**: 리스트 표기 25종을 모두 넣고, 학과 연결은 `status`가 `EXACT`(학과 이름과 똑같음, 9종)·`CONFIRMED`(사람이 확정, 16종 — 10/1 15종, 10/2 '중어전공')인 것만 적재한다. `DRAFT`는 교육통계 대계열·학과명으로 만든 초안이라 적재하지 않는다. 2026-2는 25종 모두 연결됐다(`DRAFT` 없음). 새 표기는 `DRAFT`로 넣고, 확정하면 `CONFIRMED`로 바꿔 다시 만든다.
- **id**: `curated/ids.json`이 기관(표준명)·직무(표준명|리스트 직무 칸 첫 줄)·근로지(표준명|주소) → id를 기억한다. 한 번 준 id는 바꾸지 않는다. 리스트에서 직무 이름이 바뀌면 새 id가 생기고 옛 직무는 지워진다(담아 둔 지망도 함께).
- **수기**: 2026-2 참여기관에 연결되는 것만(`common/jobs_sheet.canon`). 부서와 실습 내용·쪽만 넣고 이름·학과·학년·사진·소감은 넣지 않는다.
- **리플레이**(`replay.py`): 모집기간(7/13~7/24) 일별 가상 신호. 직무별 지원 의사 합 = 최종 배정 수(실제 값), 날짜는 시드 고정 난수. 자세한 규칙은 파일 머리 주석.

## 넣지 않는 것 (ADR-0004)
사업자번호·대표자명·매출액·기타사항, 학과×직무 매칭 집계, 과거 학기 기관별 매칭 수(`round_result`), 수기의 개인 정보. 근거 인용문에 사업자번호·대표자로 보이는 문자열이 있으면 `build_seed.py`가 멈춘다.

## 아직 비어 있는 것
- `area`(사는 곳): 서울·인천·경기 시·군·구 이름과 행정표준코드(5자리)만 둔다. 2026년 구역 변경(인천 7/1 개편 등)이 반영된 최신 코드표 파일을 받아 넣는다(대기). 그 전까지 통근 출발점은 서경대뿐이다.
- 좌표는 시드에 없다. 통근 조회 때 카카오 주소 검색으로 그때 구하고 버린다(ADR-0007).
- `job_embedding`(E5)·`round_result`(센터 동의 뒤 로컬 적재)는 이 시드가 건드리지 않는다.
