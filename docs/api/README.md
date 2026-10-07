# API 계약

프론트는 이 문서의 응답 예시로 목업을 만들고, 백엔드는 이 형태를 지킨다. 형태를 바꾸려면 이 문서를 먼저 고친다.
Notion API LIST는 이 문서의 사본이다. 둘이 다르면 이 문서가 맞다.

**Swagger**: https://coop-radar-api.onrender.com/swagger-ui.html (배포 서버). 드롭다운 '계약'은 이 문서와 예시 JSON으로 만든 26개 전부, '구현'은 지금 코드에 있는 것만 보여 준다(ADR-0011).
- '계약' 스펙은 `python scripts/build_openapi.py`가 이 폴더로 만든다(`src/main/resources/static/openapi/contract.json`). 이 문서나 예시 JSON을 고쳤으면 다시 돌려 같은 PR에 넣는다. 예시가 스키마(타입·null·코드값·범위)에 안 맞으면 여기서 실패한다.
- 새 엔드포인트는 스크립트의 `ENDPOINTS`(요청·응답 스키마와 예시 파일)와 `S`(스키마)에도 넣는다.

## 공통
- 기본 경로 `/api`, JSON, 필드는 camelCase, 날짜는 ISO 8601(`2026-07-18`, `2026-09-30T14:00:00+09:00`).
- **인증**: `Authorization: Bearer <accessToken>`. 쿠키는 쓰지 않는다 — 프론트(Cloudflare Pages, `pages.dev`)와 Render(`onrender.com`)는 사이트가 달라 서드파티 쿠키 차단에 걸린다.
  - 토큰은 JWT(HS256, 서명 키는 환경변수 `JWT_SECRET`). 만료는 가입 계정 7일, 체험 계정 24시간(계정도 그때 지운다).
  - 로그아웃은 프론트가 토큰을 버리는 것으로 끝난다(서버 호출 없음).
- **권한**: `공개` / `로그인`(역할 무관) / `STUDENT` / `CENTER`. 아래 목록의 '권한' 열.
- **오류**: HTTP 상태 코드 + `{"code": "...", "message": "사람이 읽는 설명"}`. 입력 검증 실패(`INVALID_INPUT`)만 `fields`를 더 준다. 코드 표는 맨 아래.
- **개인정보**
  - 프로필(학과·학년·평점 등)은 요청 **본문**으로만 보낸다. URL·쿼리에 넣지 않는다 — 그래서 판정·추천·지망 점검·통근 조회가 GET이 아니라 POST다.
  - 서버에 저장하는 건 `PUT /api/me/profile`에 `consent: true`로 보냈을 때뿐이다. 탈퇴하면 즉시 지운다.
  - 사는 곳은 시·군·구까지만 받는다(`homeAreaCode`). 정확한 주소는 받지 않는다.
  - 요청·응답 본문을 로그에 남기지 않는다.
- **모집 신호 = 관심**: 관심은 **내 지망에 담은 사람 수**다(순위와 상관없이 [담기]한 사람, 1인 1표). 모집기간 리플레이 가상 값에 실제 사용자가 담은 수를 더해 보여 준다(ADR-0019). 신호를 주는 응답에는 `isVirtual`과 `signalSource`를 반드시 넣는다.
- **호출 제한**(초기값, 설정으로 조정): 체험 계정 만들기 IP당 1시간 300회(환경변수 `GUEST_PER_IP_PER_HOUR`, 0이면 끔 — 시연장·학교 와이파이는 여럿이 한 IP) → 넘으면 429 + `Retry-After`(초). 이유 문장(LLM) 계정당 1시간 30회·IP당 60회 → 넘으면 **200 + 기본 문장**(화면이 깨지지 않게). 통근 조회 계정당 1시간 30회·서버 전체 하루 900회(카카오 무료 하루 1,000건 안에서 멈춤) → 넘으면 **200 + `available: false`**.
- **실행 중 외부 호출**은 Claude(이유 문장)·카카오(통근 조회 — 주소 검색과 대중교통) 둘뿐이다(ADR-0002, ADR-0007). 임베딩은 쓰지 않는다(E5 결과, ADR-0018). 둘 다 실패해도 200으로 화면을 유지한다.
- **코드값**은 영문 대문자이고 DB CHECK와 같은 집합이다(`V1__init.sql`). 화면 표기는 `GET /api/codes`에서 가져간다.
- **예시 값**: 기관·직무·인용문은 전부 가상이다(`(가상)` 표시). 실제 값은 시드에서 나온다. 목록 응답의 예시는 일부 행만 보여 준다.
- CORS: `CorsConfig`가 `GET`·`POST`·`PUT`·`DELETE`·`OPTIONS`와 모든 헤더(`Authorization` 포함)를 허용한다(Backend#16). 통근 조회에는 환경변수 `KAKAO_REST_API_KEY`(카카오 디벨로퍼스 REST API 키)가 필요하다.

## 목록
| # | 구분 | 메서드 | 경로 | 권한 | 화면 | 설명 | 예시 |
|---|---|---|---|---|---|---|---|
| 1 | 공통 | GET | `/api/ping` | 공개 | — | 배포 연결 확인 | [응답](ping.json) |
| 2 | 공통 | GET | `/api/codes` | 공개 | 전체 | 코드값 → 화면 표기 | [응답](codes.json) |
| 3 | 인증 | POST | `/api/auth/signup` | 공개 | 가입 | 이메일 가입 | [요청](auth-signup.request.json) · [응답](auth-token.json) |
| 4 | 인증 | POST | `/api/auth/login` | 공개 | 로그인 | 이메일 로그인 | [요청](auth-login.request.json) · [응답](auth-token.json) |
| 5 | 인증 | POST | `/api/auth/guest` | 공개 | 시작 | 체험 계정 만들기([예시 프로필로 시작]·[센터 담당자로 보기]) | [요청](auth-guest.request.json) · [응답](auth-guest.json) |
| 6 | 내 정보 | GET | `/api/me` | 로그인 | M1·공통 | 내 계정 | [응답](me.json) |
| 7 | 내 정보 | DELETE | `/api/me` | 로그인 | M1 | 탈퇴(계정·프로필·담은 지망 즉시 삭제) | 204 |
| 8 | 내 정보 | GET | `/api/me/profile` | STUDENT | S1 | 저장한 프로필 | [응답](me-profile.json) |
| 9 | 내 정보 | PUT | `/api/me/profile` | STUDENT | S1 | 프로필 저장(동의 필수) | [요청](me-profile.request.json) · [응답](me-profile.json) |
| 10 | 내 정보 | DELETE | `/api/me/profile` | STUDENT | M1 | 저장한 프로필만 삭제 | 204 |
| 11 | 기준 정보 | GET | `/api/departments` | 공개 | S1 | 학과(교육통계 재학생이 있는 60개, ADR-0014) | [응답](departments.json) |
| 12 | 기준 정보 | GET | `/api/areas` | 공개 | S1 | 사는 곳 선택지(서울·인천·경기 시·군·구 83곳, 행정표준코드) | [응답](areas.json) |
| 13 | 기준 정보 | GET | `/api/rounds/current` | 공개 | S5·C4 | 현재 모집 회차와 리플레이 날짜 범위 | [응답](rounds-current.json) |
| 14 | 판정·추천 | POST | `/api/eligibility` | STUDENT | S2·S4 | 회차 직무 전부의 3층 판정 | [요청](profile-body.request.json) · [응답](eligibility.json) |
| 15 | 판정·추천 | POST | `/api/recommendations` | STUDENT | S3 | 적합도 추천 상위 5개 | [요청](profile-body.request.json) · [응답](recommendations.json) |
| 16 | 판정·추천 | POST | `/api/recommendations/{jobId}/reason` | STUDENT | S3 | 추천 이유 문장(LLM, 실패 시 기본 문장) | [요청](profile-body.request.json) · [응답](recommendation-reason.json) |
| 17 | 직무 | GET | `/api/jobs/{jobId}` | 로그인 | S4 | 직무 상세 + AI 추출 근거 | [응답](job-detail.json) |
| 18 | 직무 | POST | `/api/jobs/{jobId}/commute` | 로그인 | S4 | 통근 시간(카카오 주소 검색 + 대중교통 실시간, 저장 안 함) | [요청](commute.request.json) · [응답](commute.json) · [실패](commute-unavailable.json) |
| 19 | 지망 | GET | `/api/me/plan` | STUDENT | S3·S5 | 담은 직무와 순위 | [응답](me-plan.json) |
| 20 | 지망 | POST | `/api/me/plan/items` | STUDENT | S3 | 담기 | [요청](me-plan-items.request.json) · 201/200 |
| 21 | 지망 | DELETE | `/api/me/plan/items/{jobId}` | STUDENT | S3·S5 | 담기 취소 | 204 |
| 22 | 지망 | PUT | `/api/me/plan/ranks` | STUDENT | S5 | 1~3지망 순위 정하기 | [요청](me-plan-ranks.request.json) · [응답](me-plan.json) |
| 23 | 지망 | POST | `/api/me/plan/check` | STUDENT | S5 | 지망별 모집 신호 + 빈 자리 제안 | [요청](me-plan-check.request.json) · [응답](me-plan-check.json) |
| 24 | 센터 | GET | `/api/center/board?asOf=` | CENTER | C4 | 모집 현황판 | [응답](center-board.json) |
| 25 | 직무 | GET | `/api/jobs/{jobId}/views` | 로그인 | S4·C4 | 직무 조회수(학생 계정마다 직무별 하루 1번) | [응답](job-views.json) |
| 26 | 기준 정보 | GET | `/api/certificates` | 공개 | S1 | 자격증 선택지(이번 회차 직무가 요구·우대하는 것만, ADR-0021) | [응답](certificates.json) |

## 공통 객체
**Profile** (요청 본문의 `profile`, `PUT /api/me/profile`)
| 필드 | 타입 | 규칙 |
|---|---|---|
| `departmentId` | number | `GET /api/departments`의 id |
| `grade` | number | 1~4 |
| `completedSemesters` | number | 0~8 |
| `gpa` | number | 0.0~4.5, 소수 첫째 자리까지 |
| `graduationExpected` | boolean | 다음 졸업(2026-2 회차 → 2027년 2월) 예정이면 true |
| `interestText` | string \| null | 200자 이하. 적합도 추천의 질의 |
| `homeAreaCode` | string \| null | 사는 곳. `GET /api/areas`의 `code`(시·군·구 5자리). 선택 — null이면 통근을 서경대에서 출발로 본다. 통근 조회에만 쓴다 |
| `certificates` | string[] \| null | 가진 자격증. `GET /api/certificates`의 `code` 목록(20개 이하, 중복은 하나로 본다). 선택 — null이거나 빠지면 `[]`(없음)과 같다. 판정의 자격증 줄에만 쓴다([ADR-0021](../decisions/0021-certificate-profile.md), [ADR-0024](../decisions/0024-verdict-clarity-and-fit-sentences.md)) |

**판정** — `verdict`는 `ELIGIBLE`(지원 가능) · `NEEDS_CHECK`(확인 필요) · `INELIGIBLE`(지원 불가).
- 이유 한 줄은 `{layer, item, requirement, mine, result, alertId?, citation?}`. `layer`는 `SCHOOL_RULE` · `INSTITUTION` · `MAJOR`, `result`는 `MET` · `NOT_MET` · `CHECK` · `INFO`.
- 정하는 순서([ADR-0024](../decisions/0024-verdict-clarity-and-fit-sentences.md)): `SCHOOL_RULE`이나 `INSTITUTION`에 `NOT_MET`(못 맞춘 조건 — 이수 학기·졸업예정자 계절제·학년·학점·필수 자격증)이 하나라도 있으면 `INELIGIBLE` → 아니면 `INSTITUTION`에 `CHECK`(챙길 것 — 필수 포트폴리오, 문서끼리 엇갈린 판정 항목)가 하나라도 있으면 `NEEDS_CHECK` → 아니면 `ELIGIBLE`. `MAJOR`는 판정에 넣지 않는다(참고 표시).
- 학교 규정(`SCHOOL_RULE`) 행은 두 가지뿐이다(근거: 2026-2 학생 모집안내, 10/1 확정). 결과는 `MET`·`NOT_MET`만 쓴다.

  | `item` | `requirement` | 쓰는 값 | `NOT_MET` |
  |---|---|---|---|
  | 이수 학기 | 4학기 이상 | `completedSemesters` | 4 미만 |
  | 졸업예정자 계절제 | 졸업예정자는 방학 과정 불가 | `graduationExpected`, 직무 `course` | `graduationExpected`가 true이고 `course`가 `VACATION` |

  - 이수 학기 행은 모든 직무에, 졸업예정자 계절제 행은 `course`가 `VACATION`인 직무에만 붙는다. `VACATION_SEMESTER`(방학·학기 연계)가 계절제에 드는지는 센터 확인 항목이라, 확인 전까지는 이 행을 붙이지 않는다.
  - 모집안내의 나머지 참여 제한 네 가지(휴학·수료·학사학위취득유예·대학원생, 현장실습 학점 18학점 초과, 재직 중, 부정 실습 제재)는 프로필로 묻지도 저장하지도 않는다. 화면에 고정 안내로만 보여 준다(프론트 상수, 최소 수집 — ADR-0008).
- 기관 조건(`INSTITUTION`) 행: 학년(`gradeRule` — `Y3_4` 3학년 이상 / `Y4` 4학년 / `GRADUATING` `graduationExpected`) · 학점(`gpaMin`이 있을 때만, 같으면 `MET`) · 포트폴리오(`REQUIRED`면 `CHECK`, `PREFERRED`면 `INFO` — 판정에 안 들어감, `NONE`이면 행 없음. `mine`은 '직접 준비' — 프로필로 묻지 않고 학생이 준비해서 낸다) · 자격증(아래 표). 학년·학점 미충족은 `NOT_MET`이고 판정은 `INELIGIBLE`이다(ADR-0024, 10/7 — 전에는 `NEEDS_CHECK`).
- 자격증 행(`item` '자격증', [ADR-0021](../decisions/0021-certificate-profile.md)·[0024](../decisions/0024-verdict-clarity-and-fit-sentences.md)): `requirement`는 '필수 (원문)' · '우대 (원문)', 직무에 자격증 요건이 없으면(`NONE`) 행 없음. 프로필에 없으면(`certificates` null 포함) 없음이다.

  | 직무 | 직무의 자격증 코드가 프로필에 있음 | 없음(`certificates` null·`[]` 포함) |
  |---|---|---|
  | 필수(`REQUIRED`) | `MET` '있음' | `NOT_MET` '없음' → `INELIGIBLE` |
  | 우대(`PREFERRED`) | `INFO` '있음' | `INFO` '없음' |

- `citation`(ADR-0023): 그 행의 판정이 쓴 요건 원문 `{sourceType, documentTitle, page, quote}`(Citation과 같은 모양, 원문 파일 링크 없음). `quote`는 원문 그대로(앞의 ■·-·* 글머리표만 뗌)다.

  | 행 | `sourceType` | `documentTitle` | `page` | `quote` 예 |
  |---|---|---|---|---|
  | 이수 학기 · 졸업예정자 계절제 | `SCHOOL_NOTICE` | 2026학년도 2학기 표준 현장실습학기제 학생 모집안내 | null(웹 공지) | 4학기 이상 수료한 재학생(…) · 졸업예정자의 계절제 참여 불가 |
  | 학년 · 학점 · 포트폴리오 · 선호 전공 | `INSTITUTION_LIST` | 2026학년도 2학기 표준 현장실습학기제 참여기관 리스트 | null(엑셀) | 학년 : 3, 4학년 · 학점 3.5 이상 · 포트폴리오 필수 제출 · 전공 : 헤어디자인학과 |
  | 자격증 | `OPERATION_PLAN` | <기관> 운영계획서 | 쪽 | 미용 자격증 or 미용 면허증 소지자 |

  - 판정이 리스트 값을 쓰는 항목(학년·학점·선호 전공·리스트의 포트폴리오 필수)은 리스트 칸, 계획서 값을 쓰는 항목(자격증, 리스트에 없는 포트폴리오 요건, 계획서가 '전공 무관'이라 전공 무관으로 본 선호 전공 — [ADR-0025](../decisions/0025-major-open-by-plan-and-renamed-department.md))은 계획서 쪽·인용이다.
  - 검토 알림으로 새로 만든 행(`alertId`, 원래 행이 없던 항목)에는 없다 — 알림 자체가 두 원문을 갖고 있다(직무 상세·현황판 `alerts`). 원래 행이 알림으로 `CHECK`가 된 경우는 원래 출처가 남는다. 출처를 못 찾은 행에도 필드가 없다.
- 검토 알림(M2)의 `fieldKey`가 판정 항목(`gradeRequirement`·`gpaRequirement`·`portfolio`·`certificate`)이면 그 항목 행이 `CHECK`가 되고 `alertId`가 붙는다. 그 밖의 알림(선호 전공·기간·지원비·기관 현황 등)은 판정을 바꾸지 않는다 — 학생 목록은 `alertCount`로 '문서 검토' 꼬리표만 단다([ADR-0016](../decisions/0016-demo-profile-and-screen-rules.md)). 알림 행은 `item` '<필드 표기> 표기'(예: '학점 요건 표기'), `requirement`는 알림 종류별 문장, `mine` '—'. `alertId`가 없는 행에는 필드 자체가 없다.
- `majorMatch`는 `MATCH` · `NOT_LISTED` · `OPEN`(전공 무관). `MATCH`는 직무의 선호 전공 표기가 사람이 확정한 학과 매핑(M3)에 내 학과가 있을 때다 — 확정 전 표기(`DRAFT`, 2026-2는 없음)는 누구에게도 `MATCH`가 아니다. `MAJOR` 행은 직무마다 하나, `INFO`.

**Stipend** — `{basis, amount, minWageRatio}`. `basis`가 `MONTHLY`면 월액, `HOURLY`면 시급(원). `minWageRatio`는 2026 최저임금(월 2,156,880원 / 시 10,320원) 대비 %, 소수 첫째 자리.

**Signal** — `{interest, liveInterest, headcount, ratio, status, closesOn, closeReason, closesOnIsVirtual, expectedFullOn}`
- `interest`(관심)는 asOf까지 누적한 **내 지망에 담은 사람 수** = 리플레이 가상 값 + 실제 사용자가 담은 수(ADR-0019). `ratio` = interest ÷ headcount(소수 둘째 자리).
  - 가상 값: 직무별 합 = 최종 배정 수(실제 값), 날짜는 시드 고정 난수(`replay_signal`).
  - 실제 값(`liveInterest`): 지금 그 직무를 담아 둔 계정 수(`plan_item`, 순위 무관, 1인 1표, 체험 계정 포함 — 체험 계정이 24시간 뒤 지워지면 그 수도 빠진다). 시연 속 '오늘'인 `replay.defaultAsOf`(7/23)에 생긴 관심으로 더한다 — asOf가 그보다 앞이면 0, 그날 이미 마감(`closesOn` ≤ 기준일)인 직무에도 0.
  - 지망 점검(#23)은 본인이 담은 것을 빼고 **다른 사람** 수만 센다. 현황판(#24)은 모두 센다.
- `status`: asOf가 회차 종료일보다 뒤이거나 `closesOn` ≤ asOf면 `CLOSED` → 아니면 `OPEN`. 관심이 정원을 넘어도 몰림 상태·경고는 주지 않는다(ADR-0015, 10/1 회의). 숫자(`interest`·`headcount`·`ratio`)는 그대로 준다.
- `closeReason`: `APPLICATION_DEADLINE`(운영계획서 접수마감일자) · `CENTER_CLOSED`(센터 리스트 모집마감). `closesOnIsVirtual`이 true면 날짜가 생성기가 정한 가상 값이다.
- `closesOn`은 '이 날부터 지원 불가'다 — 화면은 하루 전 날짜를 마감일로 보여 준다(예: `closesOn` 7/18 → '7/17 마감').
- `expectedFullOn`: 정원 도달 예상일. 최근 3일 관심 평균 증가량(실제 담은 수 포함)으로 외삽하고, 모집기간 안에 닿지 않거나 이미 닿았으면 null.
  - 최근 3일 = asOf와 그 앞 이틀(모집 시작 전 날은 빼고 남은 날 수로 나눈다). 남은 자리 ÷ 하루 평균을 올림한 날 수만큼 asOf에서 더한다.
  - null: 이미 정원 이상 · `CLOSED` · 최근 3일 관심 증가 0 · 지원할 수 있는 마지막 날(회차 종료일, `closesOn`이 있으면 그 전날)을 넘김.

**InstitutionRef** — `{id, name, logoPath}`. 판정·추천·지망·지망 점검·현황판·검토 알림의 `institution`. 직무 상세의 `institution`(규모·소재지 등이 더 있는 객체)에도 같은 `logoPath`가 있다(ADR-0019).
- `logoPath`: 기관 로고 PNG 경로(API 서버 기준, 예: `/logos/3.png`). 프론트는 API 기본 주소(`VITE_API_BASE_URL`) 뒤에 붙여 `<img>`로 띄운다. 로그인 없이 받고(`/api` 밖이라 CORS도 필요 없다), 캐시는 하루. 로고가 없으면 null → 기관명 첫 글자로 대신한다. 2026-2 기관 18곳은 모두 있다.
- 로고는 투명 배경 PNG이고 여백을 잘라 낸 원래 비율이다(최대 600×200). 가로로 긴 글자 로고와 정사각형 아이콘이 섞여 있으니 고정 크기 상자에 `object-fit: contain`으로 넣는다.

**Citation** — `{sourceType, documentTitle, page, quote}`. `sourceType`은 `OPERATION_PLAN` · `TESTIMONIAL`(추천 근거), 판정 이유 행의 `citation`은 `INSTITUTION_LIST` · `SCHOOL_NOTICE`도 쓰고 그때 `page`는 null이다. 원문 PDF 링크는 주지 않는다.

**적합도** — `fit`은 `HIGH` · `MEDIUM`. 점수는 응답에 넣지 않는다(정렬에만 쓴다). 규칙 + 키워드이고 임베딩은 쓰지 않는다(E5 결과, ADR-0018).
- 점수 = 5 × 선호 전공 일치(`MATCH`·`OPEN`) + 2 × 관심 키워드 유사도(0~1) + 1 × `ELIGIBLE` + 0.5 × 지원비(최저임금 대비 75% → 0, 100% 이상 → 1) + 0.5 × 채용연계형. 같은 점수면 리스트 순번. 선호 전공이 맞는 직무가 늘 먼저다.
- 관심 키워드 유사도: `interestText` ↔ 직무 텍스트(부서·직무명·직무 개요·교육 목표·요구 역량·주차 계획·기관 업태·종목)의 글자 2~3-gram TF-IDF 코사인을 후보 중 최댓값으로 나눈 값. `interestText`가 없으면 0.
- `HIGH`: 선호 전공이 맞고, `interestText`가 없거나 관심 유사도 ≥ 0.5. 그 밖은 `MEDIUM`.
- **추천할 이유**([ADR-0022](../decisions/0022-recommendation-floor.md)): 선호 전공이 맞거나(`MATCH`·`OPEN`), 관심 문장과 겹친다 — 원 코사인(최댓값으로 나누기 전)이 0.02 이상이면서, 0.05 이상이거나 관심 유사도 ≥ 0.5(가장 많이 겹친 직무의 절반 이상). 둘 다 아니면 점수가 있어도 추천하지 않는다. `interestText`가 없으면 선호 전공이 맞는 직무만 추천한다.

## 엔드포인트별 규칙
**인증**
- `signup`: 이메일은 소문자로 맞춰 저장, 비밀번호 8자 이상·UTF-8 72바이트 이하(BCrypt 한도 — 영문 72자, 한글 24자). 이메일 인증은 없다(MVP). 201 + 토큰. 이미 있으면 409 `EMAIL_TAKEN`(대소문자만 달라도 같은 이메일).
- `login`: 틀리면 401 `LOGIN_FAILED`. 이메일과 비밀번호 중 무엇이 틀렸는지 말하지 않는다(응답 본문도 같다).
- `guest`: `role`은 `STUDENT` 또는 `CENTER`. `STUDENT`는 예시 프로필(메이크업디자인학과 3학년)이 저장된 체험 계정을 만들고 응답에 그 프로필을 준다(`isExample: true`). 값과 고른 이유는 [ADR-0016](../decisions/0016-demo-profile-and-screen-rules.md). `CENTER`는 현황판용이고 `profile`은 null. 201 + 토큰. CENTER 역할은 이 경로와 시드로만 생기고 가입으로는 못 만든다.
  - 예시 프로필의 사는 곳은 노원구(`11350`)다. `area` 시드에 그 코드가 없으면 null로 둔다(ADR-0007).
  - 24시간이 지난 체험 계정은 서버가 10분마다 계정째 지운다. 지우기 전이라도 그 토큰은 401 `TOKEN_EXPIRED`.
  - 호출 제한의 IP는 `CF-Connecting-IP` 헤더(Render 앞단 Cloudflare가 넣음)로 센다. 없으면 연결 주소(ADR-0013).
  - `department` 시드에 예시 학과가 없으면 STUDENT 체험도 계정만 만들고 `profile`은 null, `hasProfile`은 false다(서버가 기동 때 경고). 시드가 들어오면 다음 요청부터 예시 프로필이 붙는다.
- 토큰: 역할·체험 여부는 토큰이 아니라 매 요청 DB에서 읽는다. 탈퇴했거나 정리된 계정의 토큰은 401 `AUTH_REQUIRED`.

**내 정보**
- `DELETE /api/me` → 204. 계정·프로필·담은 지망이 함께 지워진다(DB cascade). 직무 조회 기록은 조회수로 남고 계정 연결만 끊긴다(`job_view.user_id` NULL).
- `PUT /api/me/profile`: `consent`가 true가 아니면 400 `CONSENT_REQUIRED`. `GET`에 저장한 게 없으면 404 `PROFILE_NOT_FOUND`. `certificates`는 보낸 그대로(null·`[]`·코드 목록) 저장하고 돌려준다 — 코드는 중복을 빼고 `GET /api/certificates` 순서로 맞춘다.
- `hasProfile`(`/api/me`)이 false여도 판정·추천은 된다 — 프론트가 입력받은 프로필을 본문에 넣어 보내면 된다.

**자격증 선택지**(`GET /api/certificates`, [ADR-0021](../decisions/0021-certificate-profile.md)) — `{certificates: [{code, label}]}`. 현재 회차 직무가 요구(`REQUIRED`)하거나 우대(`PREFERRED`)하는 자격증만, 코드표 순서로 준다(2026-2는 2개). 판정에 쓰지 않는 자격증은 묻지 않는다(최소 수집).
- 프로필 화면(S1)의 체크 목록으로 쓴다. 하나도 고르지 않았으면 `certificates: []`(없음)를 보낸다. 필드를 빼면(null) 없음과 같다 — **묻지 않으면 필수 자격증 직무(2026-2 미용 시술 보조)는 지원 불가로 보인다**(ADR-0024).

**판정·추천**
- `eligibility`: 회차 직무 **전부**(2026-2는 40행)를 돌려준다. S4의 '요건 ↔ 내 판정'도 이 응답의 해당 행을 쓴다(별도 API 없음). 순서는 판정(`ELIGIBLE` → `NEEDS_CHECK` → `INELIGIBLE`) → 센터 참여기관 리스트 순번. 학과가 `departments`에 없으면 400 `INVALID_INPUT`(`profile.departmentId`), 자격증 코드가 `certificate` 코드표에 없으면 400 `INVALID_INPUT`(`profile.certificates`). `homeAreaCode`는 판정에 쓰지 않아 형식만 본다.
  - `closing`: `{closesOn, closeReason, closesOnIsVirtual}` — 직무 상세의 `closing`과 같은 값이다(기준일과 상관없이 고정, 마감이 없으면 `closesOn`·`closeReason`이 null). 목록은 `closesOn` ≤ 기준일(아래 `recommendations`와 같다)이면 '마감' 꼬리표를 단다.
  - `alertCount`: 그 직무에 걸린 검토 알림 수. `jobId`가 그 직무인 알림만 세고, 기관 단위 알림은 세지 않는다.
- `recommendations`: `INELIGIBLE`과 기준일(리플레이 중에는 `rounds/current`의 `replay.defaultAsOf`, 운영 때는 오늘)에 마감된(`closesOn` ≤ 기준일) 직무를 뺀 직무 중 **추천할 이유가 있는 직무**(위 '적합도')를 적합도 점수 순으로 **5개까지** 준다 — 이유가 있는 직무가 적으면 5개보다 적고, 0개일 수도 있다. 점수는 응답에 넣지 않는다. 이유는 `reasonTemplate`(규칙 문장)을 바로 주고 `reasonStatus: PENDING`이면 프론트가 카드마다 16번을 부른다. 0개면 `items: []`와 `blockedBy: [{item, count}]` — 지원 불가로 막은 항목별 직무 수(못 맞춘 판정 이유 줄의 `item`, 처음 나온 순서. 예: 2학년·3학기면 이수 학기 40 · 학년 40 · 학점 5 · 자격증 1)와, 지원 불가는 아니지만 마감돼 빠진 직무 수(`item` '모집 마감'), 지원할 수 있고 마감 전이지만 선호 전공도 관심 문장도 맞지 않아 뺀 직무 수(`item` '관심 분야'). 1~4개일 때는 `blockedBy`가 `[]`이다.
  - `reasonTemplate`(ADR-0020·[0024](../decisions/0024-verdict-clarity-and-fit-sentences.md)): '왜 나에게 맞는지'를 내 값으로 먼저 말한다. 한 문장씩 이 순서로 잇는다.
    1. 판정 한 줄 — 판정 이유 줄(요건 ↔ 내 값)을 그대로 옮긴다.
       - `ELIGIBLE`: 갖춘 기관 조건을 내 값과 함께. "3학년·학점 3.4라 지원 조건(3·4학년, 학점 3.0 이상)을 모두 갖췄어요." · "졸업 예정이라 지원 조건(졸업예정자)을 모두 갖췄어요." · 필수 자격증이 있으면 "4학년이고 미용 자격증·면허증이 있어 지원 조건(3·4학년, 미용 자격증·면허증)을 모두 갖췄어요."(자격증 이름은 코드표 `label`)
       - `NEEDS_CHECK`: 챙길 것이 포트폴리오뿐이면 "포트폴리오만 준비해서 내면 지원할 수 있어요.", 아니면 "지원하기 전에 챙길 것이 있어요." 뒤에 하나씩("포트폴리오를 꼭 내야 해요." · 검토 알림이 걸린 항목은 "공고 문서끼리 학점 조건이 다르게 적혀 있어 현장실습지원센터에 확인이 필요해요."). 갖춘 조건이 있으면 "3학년이라 나머지 조건(3·4학년)은 갖췄어요."
       - `INELIGIBLE`(이유 문장 #16에서만 — 추천에는 안 나온다): "지금은 지원할 수 없어요." 뒤에 못 맞춘 조건마다 한 문장 — "현장실습은 4학기 이상 마쳐야 지원할 수 있는데 지금 3학기를 마쳤어요." · "졸업 예정자는 방학 과정에 참여할 수 없어요." · "4학년만 지원할 수 있는데 지금 3학년이에요." · "졸업 예정자만 지원할 수 있어요." · "학점 3.5 이상이어야 하는데 지금 3.4예요." · "미용 자격증·면허증이 있어야 하는데 프로필에 없어요."
    2. 내 학과와 선호 전공(지원 불가면 없음) — 포함 "메이크업디자인학과는 회사가 선호하는 전공('미용예술대학')에 들어가요."(표기가 학과 이름과 같으면 "광고홍보콘텐츠학과는 회사가 선호하는 전공이에요.") · 전공 무관 "전공을 따지지 않는 자리예요." · 미포함 "군사학과는 회사가 선호하는 전공에는 없지만, 선호 전공은 지원 자격과는 상관없어요."
    3. 관심 분야 — 관심 유사도가 높을 때(적합도 HIGH 기준과 같음)만. 겹친 원문을 그대로 짚는다: "관심 분야가 직무 개요 '자사 SNS 채널 콘텐츠 운영 및 홍보 마케팅 업무 지원'과 겹쳐요." — 40자 넘는 원문이면 "관심 분야와 직무 내용이 가까워요."
    4. 채용연계형이면 "채용연계형 자리예요(실습 뒤 채용으로 이어질 수 있는 유형)."
    5. 추천 5개 안에 같은 기관·같은 팀의 다른 직무가 있고 요건(요구 역량 항목·학년·학점·포트폴리오·자격증) 차이가 1~2개면: "같은 팀의 해외 마케팅과 달리 '영어 가능자' 요건은 없어요." / "… 요건이 있어요."
  - `citations`(ADR-0020): 최대 2개, 선배 수기 → 운영계획서 순. 인용은 원문 글자 그대로다(앞의 글머리표·번호·[머리말]만 뗀다).
    - 선배 수기: **같은 팀** 수기만(띄어쓰기·숫자·괄호·끝의 팀/부/실/본부를 떼고 한쪽이 다른 쪽을 품으면 같은 팀, 직무명과도 비교). 그중 관심 문장과 가장 많이 겹치는 실습 내용 한 줄(유사도 0.10 이상), 없으면 가장 최근 수기의 첫 줄. 같은 팀 수기가 없으면 붙이지 않는다.
    - 운영계획서: 직무 개요·요구 역량·주차 계획·교육 목표를 조각으로 나눠 관심 문장과 가장 많이 겹치는 조각(유사도 0.15 이상). 없거나 관심 문장이 없으면 직무 개요 첫 조각(없으면 교육 목표). 150자를 넘는 조각은 문장(마침표 뒤)으로 나누고, 그래도 길면 150자 안쪽의 띄어쓰기에서 끊는다(끊은 조각도 원문 그대로). 주차 계획은 근거 쪽이 따로 없어서 직무 개요와 전공 요건이 같은 쪽일 때만 그 쪽으로 인용한다. `reasonTemplate`가 짚은 원문과 같은 조각이다.
    - 유사도는 적합도 점수의 관심 키워드 유사도와 같은 방식(글자 2~3-gram TF-IDF, ADR-0018)이고 외부 호출이 없다.
- `reason`: `source`는 `LLM` · `CACHE` · `TEMPLATE`. LLM이 실패하거나 5초를 넘기거나 호출 제한에 걸려도 **200 + `TEMPLATE`**(`text` = 그 직무의 `reasonTemplate`). 캐시는 메모리(프롬프트 버전 + 모델 + 직무 + 학과·학년·관심 문장 + 판정·적합도 해시)라 서버가 다시 뜨면 비워진다. 없는 직무는 404 `JOB_NOT_FOUND`, `INELIGIBLE` 직무는 LLM 없이 `TEMPLATE` — `text`는 위 1번의 지원 불가 문장(못 맞춘 조건을 내 값과 함께)이다.
  - LLM(Haiku)에는 학과·학년·관심 문장과 직무 원문·근거만 보낸다. 평점·사는 곳은 보내지 않는다.
  - LLM 문장은 검증을 통과해야 `LLM`이다: 10~200자 · 해요체로 끝남('습니다'·'당신' 없음) · 근거 번호가 준 범위 안 · 따옴표 인용이 근거·직무 원문(주차 계획 포함)에 그대로 있음(공백 무시) · 선호 전공에 소속 학과가 없으면 학과 이름을 쓰지 않음 · 직무·기관 원문에 없는 관심 낱말 바로 뒤에 '브랜드·기업·회사·업계·업종·시장·산업'을 붙이지 않음(의류 회사를 '뷰티 브랜드'라고 부르는 경우) · 별표는 지운다. 다만 '선호 전공'을 말한 문장, 확인할 조건을 말한 문장('확인해야'·'확인할 것'·'확인이 필요' 등), 근거·직무 원문에 없는 넓히는 말('전 과정'·'전반'·'전체 과정'·'모든 과정'·'처음부터 끝까지'·'총괄', 공백 무시)이 든 문장은 **빼고** 남은 문장으로 본다 — 이 셋은 규칙 문장이 맡거나 원문보다 크게 말하는 것이라서. 남는 문장이 없으면 `TEMPLATE`. `citations`는 카드와 같은 근거다(ADR-0020).
  - LLM은 하는 일만 학생이 할 일로 쓴다('~을 맡아요'). `text` = `reasonTemplate`의 1번(판정 한 줄) + 2번(내 학과와 선호 전공) + LLM 문장 + 5번(같은 팀 직무와의 차이)이다(ADR-0024). 모두 `reasonTemplate`와 같은 글이라, 화면이 기본 문장을 LLM 문장으로 바꿔도 '왜 나에게 맞는지'·선호 전공·차이가 남는다. 그래서 `text`는 200자를 넘을 수 있다.
  - 키(`ANTHROPIC_API_KEY`)가 없으면 부르지 않고 `TEMPLATE`.

**직무** — 없으면 404 `JOB_NOT_FOUND`. `evidence`는 AI가 운영계획서에서 뽑은 값과 근거(허용 필드만), `seniorNotes`는 같은 기관의 선배 수기(이름·학과·학년·소감 없음). 통근 시간은 이 응답에 없다 — `workplace.hasCoordinates`가 true면 프론트가 통근 조회를 따로 부른다. 근로지 주소가 있으면 true다(좌표는 DB에 없고 통근 조회 때 카카오 주소 검색으로 구한다. 이름은 그대로 둔다).
- `requirements.majorAliases`: `[{label, departments: [{id, name}]}]` — 직무의 선호 전공 표기(`job_major_alias`)마다 확정된 학과 대응(`major_alias_department`, EXACT·CONFIRMED만 적재됨). `majorOpen`이면 `[]`. 화면의 '선호 전공 안내'(표기 → 학과)와 판정 이유의 '표기 해석'에 쓴다. 표기 순서는 표기 id 순, 학과는 id 순. 확정 전 표기(`DRAFT`, 2026-2는 없음)는 `departments: []`.
- `evidence`: 이 직무의 근거 + 그 기관의 근거(기관명·규모·소재지·접수 마감 등). 순서는 직무 필드(V1 허용 목록 순서: 부서 → 직무명 → … → 자격증) 다음 기관 필드. `label`은 서버가 붙이는 한글 표기(예: `stipendAmount` → '실습지원비').
- `alerts`: 이 직무에 걸린 알림 + 기관 전체에 걸린 알림(`jobId` null), id 순. 판정 항목이 아닌 알림(선호 전공·기간·지원비 불일치 등)도 여기에는 보인다. `documentTitle`은 `pageA`·`quoteA`(`DOC_INCONSISTENCY`면 `pageB`·`quoteB`도)가 있는 문서 이름이다(예: '소서 운영계획서', ADR-0023). `LIST_MISMATCH`의 리스트 쪽 값은 `description`에 들어 있다.
- `seniorNotes`: 최근 학기 먼저, 같은 학기는 쪽 순. `activities`는 실습 내용(원문 항목), `outcomes`는 실습 결과 문단에서 원문 그대로 자른 **사실 구절** 0~3개(60자 이내 — 만든 결과물·맡은 일·참여한 프로젝트·채택된 제안). 수기가 전부 '우수' 수기라 감상·배운 점·평가·개인 진로(입사·채용 등)는 넣지 않았다(ADR-0020). 화면에는 '우수 참여수기 기준'임을 밝힌다(`documentTitle`에 들어 있다).
- `conditions.stipend.minWageRatio`는 소수 둘째 자리에서 반올림한다. 기준이 `UNSPECIFIED`거나 금액이 없으면 null.
- 학생(`STUDENT`)이 이 응답을 받으면 조회수에 센다 — 계정마다 직무별로 하루(한국 시간) 한 번. 센터 담당자는 세지 않는다. 기록이 실패해도 상세는 그대로 준다.

**조회수**(`GET /api/jobs/{jobId}/views`, ADR-0019) — 로그인(역할 무관). 없는 직무는 404 `JOB_NOT_FOUND`.
- `{jobId, views, todayViews}`. `views`는 지금까지, `todayViews`는 오늘(한국 시간) 조회 수. 둘 다 실제 값이다(리플레이·가상 값 없음, 기준일과 상관없음).
- 이 API를 부르는 것은 조회로 세지 않는다. 조회는 직무 상세(#17)를 열 때만 센다.

**통근**(`POST /api/jobs/{jobId}/commute`, ADR-0007)
- 본문은 `{homeAreaCode}` 하나. 저장한 프로필이 있어도 프론트가 본문에 넣는다(프로필 값은 본문으로만). null이거나 빠지면 서경대에서 출발한다. `areas`에 없는 코드면 400 `INVALID_INPUT`.
- 서버가 카카오만 부른다(헤더 `Authorization: KakaoAK ${KAKAO_REST_API_KEY}`).
  1. 카카오 주소 검색(`GET https://dapi.kakao.com/v2/local/search/address.json`)으로 좌표를 구한다 — 출발 = 사는 곳 '시도 시·군·구'(예: '서울 노원구') 또는 서경대(설정값, 검색 안 함), 도착 = 근로지 주소(없으면 기관 주소)를 도로명 + 건물번호까지만 남긴 검색어. 못 찾으면 첫 쉼표 앞 원문으로 한 번 더. 출발·도착은 동시에 부른다.
  2. 카카오 대중교통 길찾기(`GET https://dapi.kakao.com/v2/routing/publictraffic`)를 1번 부른다. 응답 경로 중 totalTime이 가장 짧은 경로(같으면 환승이 적은 쪽, 그다음 먼저 온 쪽)에서 `minutes` = totalTime(초) ÷ 60 반올림, `transfers`, `fareWon` = fare.value. 카카오 문서에 경로 정렬 기준이 없어 첫 경로를 쓰지 않는다(10/2).
  - `destination.address`는 정리하기 전 원문 주소다.
- **저장하지 않는다.** 좌표·시간·경로와 결과로 만든 값을 DB·캐시에 두지 않는다(카카오 운영정책: 결과 저장·가공 데이터 저장·미리 조회해 보관 금지). 같은 직무를 다시 열면 다시 부른다. 프론트는 그 화면에 있는 동안만 상태로 들고 있는다.
- 실패해도 **200 + `available: false`**, `minutes`·`transfers`·`fareWon`은 null. `unavailableReason`: `NO_WORKPLACE`(근무지 주소가 없음 — 카카오를 부르지 않는다 — 또는 카카오 주소 검색이 근무지를 못 찾음) · `NO_ROUTE`(카카오 `NO_RESULTS`·`EQUAL_POINTS`·`STARTNODES_NULL`·`ENDNODES_NULL`) · `LIMITED`(호출 제한·하루 한도·카카오 한도 초과) · `PROVIDER_ERROR`(사는 곳을 못 찾음, 그 밖의 카카오 오류, 호출 하나가 3초 초과).
- 출발 시각은 정할 수 없다(카카오 API에 시각 값이 없다). 조회 시각에 따라 결과가 바뀌는지는 낮·밤에 한 번씩 `KakaoLiveCheck`로 확인한다.
- 화면에는 '노원구에서 약 43분 · 환승 1회'처럼 출발지를 꼭 붙이고, 출처 '카카오맵 대중교통 기준'을 적는다. 로그에 `homeAreaCode`·검색어·좌표를 남기지 않는다.

**지망**
- 담기(`POST items`): 새로 담으면 201, 이미 담겨 있으면 200(그대로). 본문 없음. 현재 회차에 없는 직무는 400 `INVALID_INPUT`(`jobId`). 취소는 204, 없으면 404 `PLAN_ITEM_NOT_FOUND`.
- `GET /api/me/plan`·`PUT ranks` 응답의 `items` 순서: 순위 있는 것(1 → 3) 먼저, 그다음 담은 순.
- `PUT ranks`는 **순위 전체**를 보낸다. 여기 없는 담은 직무는 순위가 지워진다(`rank: null`). 순위는 1~3, 중복 불가, 한 직무에 하나, 3개까지, 담은 직무만 → 어기면 400 `RANK_INVALID`(아무것도 바꾸지 않음). `ranks`가 없거나 원소의 `jobId`·`rank`가 빠지면 400 `INVALID_INPUT`. `[]`이면 순위를 모두 지운다.
- `check`: 순위가 있는 직무의 신호와 대안을 준다(경고 문장 없음, ADR-0015). `items`는 순위 순, 순위를 안 정했으면 `[]`. 본문 `{profile, asOf?}` — `profile`이 없거나 `asOf` 형식이 틀리면 400 `INVALID_INPUT`.
  - 신호의 관심은 본인이 담은 것을 뺀 다른 사람 수다(현황판과 다를 수 있다).
  - 대안(`alternatives`) = `verdict`가 `ELIGIBLE`이고, `CLOSED`가 아니고, 남은 자리(`headcount` − `interest`)가 0보다 크고, 이미 담은 직무(`plan_item`)가 아닌 직무. 적합도 점수 → 남은 자리 순으로 최대 5개([ADR-0016](../decisions/0016-demo-profile-and-screen-rules.md)). 적합도 점수는 추천과 같다([ADR-0018](../decisions/0018-recommendation-rule-keyword.md)).
  - `why`는 규칙 문장이다. 앞: 1지망과 같은 기관이면 '1지망과 같은 기관의 직무이고', 관심 문장과 겹치면(위 '추천할 이유'의 관심 기준) '관심 분야와 가깝고', 둘 다 아니면 '지원 조건을 모두 통과했고'(ADR-0022). 뒤: 관심이 0이면 '지금 담은 사람이 0명이에요.', 아니면 '남은 자리가 N개예요.' 둘을 쉼표로 잇는다(예: '1지망과 같은 기관의 직무이고, 지금 담은 사람이 0명이에요.').
- `asOf`는 회차 기간 안이어야 한다(아니면 400 `AS_OF_OUT_OF_RANGE`). 생략하면 `rounds/current`의 `replay.defaultAsOf`.

**센터** — CENTER만(아니면 403 `FORBIDDEN_ROLE`). 회차 직무 전부를 리스트 순번대로 행으로 준다. `asOf` 규칙은 지망 점검과 같다(생략하면 `replay.defaultAsOf`, 모집기간 밖이면 400 `AS_OF_OUT_OF_RANGE`, 날짜 형식이 아니면 400 `INVALID_INPUT`). 세부 정의는 [ADR-0017](../decisions/0017-center-board-details.md).
- `summary`: `jobs` 직무 수 · `seats` 정원 합 · `interestTotal` asOf까지 관심 합(가상 + 실제) · `liveInterestTotal` 그중 실제 사용자가 담은 수 · `zeroSignalJobs` 관심이 0인 직무 수 · `closedJobs` `CLOSED` 직무 수.
- `eligiblePool`(적격 학생 풀): 직무의 선호 전공 표기에서 사람이 확정한 학과(중복 없이)의 재학생 수 합. 전공 무관이면 전체 재학생. 확정 전 표기(`DRAFT`, 2026-2는 없음)는 0으로 센다.
- `risks[].code`(이 순서): `NARROW_POOL` · `PORTFOLIO_REQUIRED` · `CERTIFICATE_REQUIRED`(`detail`은 자격증 원문) · `WEEKEND`(토·일 실습, `detail` '토'·'토·일') · `DOC_ALERT`. `label`은 `codes`의 `risk` 표기.
  - `NARROW_POOL`: 적격 학생 풀(`eligiblePool`)이 200명 미만, `detail` '선호 전공 재학생 N명'. 2026-2 시드 분포(98·102·102·102·102·195·195·198명 … — 10/7 ADR-0025 전에는 195 자리가 115)에서 하위 직무를 가르는 값이다(10/2 결정, [ADR-0016](../decisions/0016-demo-profile-and-screen-rules.md)). 설정 `app.center.narrow-pool-below`(환경변수 `CENTER_NARROW_POOL_BELOW`).
  - `DOC_ALERT`: 그 직무 또는 그 기관에 검토 알림이 있음, `detail` '검토 알림 N건'.
- `alertCount`·`alerts`: 그 직무에 걸린 알림 + 기관 전체(`jobId` null)에 걸린 알림(판정 행의 `alertCount`와 달리 기관 단위도 센다 — `DOC_ALERT`와 같은 범위). `alerts`는 회차 기관들의 알림 전부, id 순.
- `historyAvailable`이 false면 `pastZeroRounds` 열을 숨긴다. 지난 회차 결과는 센터 동의 뒤에만 적재하고 원소 모양도 그때 정한다 — 그 전까지는 항상 false · `[]`.

## 오류 코드
| code | HTTP | 언제 |
|---|---|---|
| `INVALID_INPUT` | 400 | 형식·범위 위반. `fields: [{field, reason}]`를 준다 |
| `CONSENT_REQUIRED` | 400 | 프로필 저장에 동의가 없음 |
| `RANK_INVALID` | 400 | 순위가 1~3이 아니거나 중복이거나 담지 않은 직무 |
| `AS_OF_OUT_OF_RANGE` | 400 | asOf가 회차 모집기간 밖 |
| `AUTH_REQUIRED` | 401 | 토큰 없음·잘못된 토큰 |
| `TOKEN_EXPIRED` | 401 | 토큰 만료(체험 계정은 계정도 지워짐) |
| `LOGIN_FAILED` | 401 | 이메일 또는 비밀번호가 틀림 |
| `FORBIDDEN_ROLE` | 403 | 역할이 맞지 않음(학생이 현황판 등) |
| `PROFILE_NOT_FOUND` | 404 | 저장한 프로필 없음 |
| `JOB_NOT_FOUND` | 404 | 없는 직무 |
| `PLAN_ITEM_NOT_FOUND` | 404 | 담지 않은 직무를 취소 |
| `EMAIL_TAKEN` | 409 | 이미 가입한 이메일 |
| `RATE_LIMITED` | 429 | 체험 계정 만들기 호출 제한 |
| `INTERNAL` | 500 | 그 밖의 서버 오류 |

```json
{
  "code": "INVALID_INPUT",
  "message": "입력값을 확인해 주세요",
  "fields": [{ "field": "profile.gpa", "reason": "0.0~4.5, 소수 첫째 자리까지" }]
}
```
