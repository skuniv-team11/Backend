# API 계약

프론트는 이 문서의 응답 예시로 목업을 만들고, 백엔드는 이 형태를 지킨다. 형태를 바꾸려면 이 문서를 먼저 고친다.
Notion API LIST는 이 문서의 사본이다. 둘이 다르면 이 문서가 맞다.

**Swagger**: 서버를 띄우고 `/swagger-ui.html`(로컬 http://localhost:8080/swagger-ui.html). 드롭다운 '계약'은 이 문서와 예시 JSON으로 만든 24개 전부, '구현'은 지금 코드에 있는 것만 보여 준다(ADR-0011).
- '계약' 스펙은 `python scripts/build_openapi.py`가 이 폴더로 만든다(`src/main/resources/static/openapi/contract.json`). 이 문서나 예시 JSON을 고쳤으면 다시 돌려 같은 PR에 넣는다. 예시가 스키마(타입·null·코드값·범위)에 안 맞으면 여기서 실패한다.
- 새 엔드포인트는 스크립트의 `ENDPOINTS`(요청·응답 스키마와 예시 파일)와 `S`(스키마)에도 넣는다.

## 공통
- 기본 경로 `/api`, JSON, 필드는 camelCase, 날짜는 ISO 8601(`2026-07-18`, `2026-09-30T14:00:00+09:00`).
- **인증**: `Authorization: Bearer <accessToken>`. 쿠키는 쓰지 않는다 — Vercel과 Render는 사이트가 달라 서드파티 쿠키 차단에 걸린다.
  - 토큰은 JWT(HS256, 서명 키는 환경변수 `JWT_SECRET`). 만료는 가입 계정 7일, 체험 계정 24시간(계정도 그때 지운다).
  - 로그아웃은 프론트가 토큰을 버리는 것으로 끝난다(서버 호출 없음).
- **권한**: `공개` / `로그인`(역할 무관) / `STUDENT` / `CENTER`. 아래 목록의 '권한' 열.
- **오류**: HTTP 상태 코드 + `{"code": "...", "message": "사람이 읽는 설명"}`. 입력 검증 실패(`INVALID_INPUT`)만 `fields`를 더 준다. 코드 표는 맨 아래.
- **개인정보**
  - 프로필(학과·학년·평점 등)은 요청 **본문**으로만 보낸다. URL·쿼리에 넣지 않는다 — 그래서 판정·추천·지망 점검·통근 조회가 GET이 아니라 POST다.
  - 서버에 저장하는 건 `PUT /api/me/profile`에 `consent: true`로 보냈을 때뿐이다. 탈퇴하면 즉시 지운다.
  - 사는 곳은 시·군·구까지만 받는다(`homeAreaCode`). 정확한 주소는 받지 않는다.
  - 요청·응답 본문을 로그에 남기지 않는다.
- **가상 데이터**: 모집 신호는 모집기간 리플레이용 가상 데이터다. 신호를 주는 응답에는 `isVirtual`과 `signalSource`를 반드시 넣는다.
- **호출 제한**(초기값, 설정으로 조정): 체험 계정 만들기 IP당 1시간 300회(환경변수 `GUEST_PER_IP_PER_HOUR`, 0이면 끔 — 시연장·학교 와이파이는 여럿이 한 IP) → 넘으면 429 + `Retry-After`(초). 이유 문장(LLM) 계정당 1시간 30회·IP당 60회 → 넘으면 **200 + 기본 문장**(화면이 깨지지 않게). 통근 조회 계정당 1시간 30회·서버 전체 하루 900회(카카오 무료 하루 1,000건 안에서 멈춤) → 넘으면 **200 + `available: false`**.
- **실행 중 외부 호출**은 Claude(이유 문장)·임베딩·카카오 대중교통(통근 조회) 셋뿐이다(ADR-0002, ADR-0007). 셋 다 실패해도 200으로 화면을 유지한다.
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
| 12 | 기준 정보 | GET | `/api/areas` | 공개 | S1 | 사는 곳 선택지(서울·인천·경기 시·군·구) | [응답](areas.json) |
| 13 | 기준 정보 | GET | `/api/rounds/current` | 공개 | S5·C4 | 현재 모집 회차와 리플레이 날짜 범위 | [응답](rounds-current.json) |
| 14 | 판정·추천 | POST | `/api/eligibility` | STUDENT | S2·S4 | 회차 직무 전부의 3층 판정 | [요청](profile-body.request.json) · [응답](eligibility.json) |
| 15 | 판정·추천 | POST | `/api/recommendations` | STUDENT | S3 | 적합도 추천 상위 5개 | [요청](profile-body.request.json) · [응답](recommendations.json) |
| 16 | 판정·추천 | POST | `/api/recommendations/{jobId}/reason` | STUDENT | S3 | 추천 이유 문장(LLM, 실패 시 기본 문장) | [요청](profile-body.request.json) · [응답](recommendation-reason.json) |
| 17 | 직무 | GET | `/api/jobs/{jobId}` | 로그인 | S4 | 직무 상세 + AI 추출 근거 | [응답](job-detail.json) |
| 18 | 직무 | POST | `/api/jobs/{jobId}/commute` | 로그인 | S4 | 통근 시간(카카오 대중교통 실시간, 저장 안 함) | [요청](commute.request.json) · [응답](commute.json) · [실패](commute-unavailable.json) |
| 19 | 지망 | GET | `/api/me/plan` | STUDENT | S3·S5 | 담은 직무와 순위 | [응답](me-plan.json) |
| 20 | 지망 | POST | `/api/me/plan/items` | STUDENT | S3 | 담기 | [요청](me-plan-items.request.json) · 201/200 |
| 21 | 지망 | DELETE | `/api/me/plan/items/{jobId}` | STUDENT | S3·S5 | 담기 취소 | 204 |
| 22 | 지망 | PUT | `/api/me/plan/ranks` | STUDENT | S5 | 1~3지망 순위 정하기 | [요청](me-plan-ranks.request.json) · [응답](me-plan.json) |
| 23 | 지망 | POST | `/api/me/plan/check` | STUDENT | S5 | 지망별 모집 신호 + 빈 자리 제안 | [요청](me-plan-check.request.json) · [응답](me-plan-check.json) |
| 24 | 센터 | GET | `/api/center/board?asOf=` | CENTER | C4 | 모집 현황판 | [응답](center-board.json) |

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

**판정** — `verdict`는 `ELIGIBLE`(지원 가능) · `NEEDS_CHECK`(확인 필요) · `INELIGIBLE`(지원 불가).
- 이유 한 줄은 `{layer, item, requirement, mine, result, alertId?}`. `layer`는 `SCHOOL_RULE` · `INSTITUTION` · `MAJOR`, `result`는 `MET` · `NOT_MET` · `CHECK` · `INFO`.
- 정하는 순서: `SCHOOL_RULE`에 `NOT_MET`이 하나라도 있으면 `INELIGIBLE` → 아니면 `INSTITUTION`에 `NOT_MET`·`CHECK`가 하나라도 있으면 `NEEDS_CHECK` → 아니면 `ELIGIBLE`. `MAJOR`는 판정에 넣지 않는다(참고 표시).
- 학교 규정(`SCHOOL_RULE`) 행은 두 가지뿐이다(근거: 2026-2 학생 모집안내, 10/1 확정). 결과는 `MET`·`NOT_MET`만 쓴다.

  | `item` | `requirement` | 쓰는 값 | `NOT_MET` |
  |---|---|---|---|
  | 이수 학기 | 4학기 이상 | `completedSemesters` | 4 미만 |
  | 졸업예정자 계절제 | 졸업예정자는 방학 과정 불가 | `graduationExpected`, 직무 `course` | `graduationExpected`가 true이고 `course`가 `VACATION` |

  - 이수 학기 행은 모든 직무에, 졸업예정자 계절제 행은 `course`가 `VACATION`인 직무에만 붙는다. `VACATION_SEMESTER`(방학·학기 연계)가 계절제에 드는지는 센터 확인 항목이라, 확인 전까지는 이 행을 붙이지 않는다.
  - 모집안내의 나머지 참여 제한 네 가지(휴학·수료·학사학위취득유예·대학원생, 현장실습 학점 18학점 초과, 재직 중, 부정 실습 제재)는 프로필로 묻지도 저장하지도 않는다. 화면에 고정 안내로만 보여 준다(프론트 상수, 최소 수집 — ADR-0008).
- 기관 조건(`INSTITUTION`) 행: 학년(`gradeRule` — `Y3_4` 3학년 이상 / `Y4` 4학년 / `GRADUATING` `graduationExpected`) · 학점(`gpaMin`이 있을 때만, 같으면 `MET`) · 포트폴리오·자격증(`REQUIRED`면 `CHECK`, `PREFERRED`면 `INFO` — 판정에 안 들어감, `NONE`이면 행 없음). 학년·학점 미충족은 `NOT_MET`이지만 판정은 `NEEDS_CHECK`다(기관이 정하는 조건이라).
- 문서 안에서 요건이 서로 다르게 적힌 항목(M2 검토 알림)은 `CHECK`이고 `alertId`가 붙는다. 알림의 `fieldKey`가 판정 항목(`course`·`gradeRequirement`·`gpaRequirement`·`majorRequirement`·`portfolio`·`certificate`)일 때만 행을 만든다(기간·지원비 알림은 직무 상세 `alerts`에만). 그 직무에 걸린 알림과 기관 전체(`jobId` null)에 걸린 알림 둘 다 본다. 행은 `item` '<필드 표기> 표기'(예: '선호 전공 표기'), `requirement`는 알림 종류별 문장, `mine` '—'. `alertId`가 없는 행에는 필드 자체가 없다.
- `majorMatch`는 `MATCH` · `NOT_LISTED` · `OPEN`(전공 무관). `MATCH`는 직무의 선호 전공 표기가 사람이 확정한 학과 매핑(M3)에 내 학과가 있을 때다 — 확정 전 표기(중어전공)는 누구에게도 `MATCH`가 아니다. `MAJOR` 행은 직무마다 하나, `INFO`.

**Stipend** — `{basis, amount, minWageRatio}`. `basis`가 `MONTHLY`면 월액, `HOURLY`면 시급(원). `minWageRatio`는 2026 최저임금(월 2,156,880원 / 시 10,320원) 대비 %, 소수 첫째 자리.

**Signal** — `{intent, interest, headcount, ratio, status, closesOn, closeReason, closesOnIsVirtual, expectedFullOn}`
- `intent`·`interest`는 asOf까지 누적한 지원 의사·관심 수. `ratio` = intent ÷ headcount(소수 둘째 자리).
- `status`: asOf가 회차 종료일보다 뒤이거나 `closesOn` ≤ asOf면 `CLOSED` → 아니면 `OPEN`. 지원 의사가 정원을 넘어도 몰림 상태·경고는 주지 않는다(ADR-0015, 10/1 회의). 숫자(`intent`·`headcount`·`ratio`)는 그대로 준다.
- `closeReason`: `APPLICATION_DEADLINE`(운영계획서 접수마감일자) · `CENTER_CLOSED`(센터 리스트 모집마감). `closesOnIsVirtual`이 true면 날짜가 생성기가 정한 가상 값이다.
- `expectedFullOn`: 정원 도달 예상일. 최근 3일 지원 의사 평균 증가량으로 외삽하고, 모집기간 안에 닿지 않거나 이미 닿았으면 null.

**Citation** — `{sourceType, documentTitle, page, quote}`. `sourceType`은 `OPERATION_PLAN` · `TESTIMONIAL`. 원문 PDF 링크는 주지 않는다.

**적합도** — `fit`은 `HIGH` · `MEDIUM`. 점수는 응답에 넣지 않는다(정렬에만 쓴다).

## 엔드포인트별 규칙
**인증**
- `signup`: 이메일은 소문자로 맞춰 저장, 비밀번호 8자 이상·UTF-8 72바이트 이하(BCrypt 한도 — 영문 72자, 한글 24자). 이메일 인증은 없다(MVP). 201 + 토큰. 이미 있으면 409 `EMAIL_TAKEN`(대소문자만 달라도 같은 이메일).
- `login`: 틀리면 401 `LOGIN_FAILED`. 이메일과 비밀번호 중 무엇이 틀렸는지 말하지 않는다(응답 본문도 같다).
- `guest`: `role`은 `STUDENT` 또는 `CENTER`. `STUDENT`는 예시 프로필(메이크업디자인학과 3학년)이 저장된 체험 계정을 만들고 응답에 그 프로필을 준다(`isExample: true`). `CENTER`는 현황판용이고 `profile`은 null. 201 + 토큰. CENTER 역할은 이 경로와 시드로만 생기고 가입으로는 못 만든다.
  - 예시 프로필의 사는 곳(`homeAreaCode`)은 `area` 시드가 들어오기 전까지 null이다(좌표 대기, ADR-0007).
  - 24시간이 지난 체험 계정은 서버가 10분마다 계정째 지운다. 지우기 전이라도 그 토큰은 401 `TOKEN_EXPIRED`.
  - 호출 제한의 IP는 `CF-Connecting-IP` 헤더(Render 앞단 Cloudflare가 넣음)로 센다. 없으면 연결 주소(ADR-0013).
  - `department` 시드에 예시 학과가 없으면 STUDENT 체험도 계정만 만들고 `profile`은 null, `hasProfile`은 false다(서버가 기동 때 경고). 시드가 들어오면 다음 요청부터 예시 프로필이 붙는다.
- 토큰: 역할·체험 여부는 토큰이 아니라 매 요청 DB에서 읽는다. 탈퇴했거나 정리된 계정의 토큰은 401 `AUTH_REQUIRED`.

**내 정보**
- `DELETE /api/me` → 204. 계정·프로필·담은 지망이 함께 지워진다(DB cascade).
- `PUT /api/me/profile`: `consent`가 true가 아니면 400 `CONSENT_REQUIRED`. `GET`에 저장한 게 없으면 404 `PROFILE_NOT_FOUND`.
- `hasProfile`(`/api/me`)이 false여도 판정·추천은 된다 — 프론트가 입력받은 프로필을 본문에 넣어 보내면 된다.

**판정·추천**
- `eligibility`: 회차 직무 **전부**(2026-2는 40행)를 돌려준다. S4의 '요건 ↔ 내 판정'도 이 응답의 해당 행을 쓴다(별도 API 없음). 순서는 판정(`ELIGIBLE` → `NEEDS_CHECK` → `INELIGIBLE`) → 센터 참여기관 리스트 순번. 학과가 `departments`에 없으면 400 `INVALID_INPUT`(`profile.departmentId`). `homeAreaCode`는 판정에 쓰지 않아 형식만 본다.
- `recommendations`: `INELIGIBLE`을 뺀 직무 중 상위 5개. 이유는 `reasonTemplate`을 바로 주고 `reasonStatus: PENDING`이면 프론트가 카드마다 15번을 부른다. 0개면 `items: []`와 `blockedBy: [{item, count}]`(막은 요건별 직무 수).
- `reason`: `source`는 `LLM` · `CACHE` · `TEMPLATE`. LLM이 실패하거나 5초를 넘기거나 호출 제한에 걸려도 **200 + `TEMPLATE`**. 캐시는 메모리(프로필+직무+프롬프트 버전 해시)라 서버가 다시 뜨면 비워진다.

**직무** — 없으면 404 `JOB_NOT_FOUND`. `evidence`는 AI가 운영계획서에서 뽑은 값과 근거(허용 필드만), `seniorNotes`는 같은 기관의 선배 수기(이름·학과·학년 없음). 통근 시간은 이 응답에 없다 — `workplace.hasCoordinates`가 true면 프론트가 통근 조회를 따로 부른다.
- `evidence`: 이 직무의 근거 + 그 기관의 근거(기관명·규모·소재지·접수 마감 등). 순서는 직무 필드(V1 허용 목록 순서: 부서 → 직무명 → … → 자격증) 다음 기관 필드. `label`은 서버가 붙이는 한글 표기(예: `stipendAmount` → '실습지원비').
- `alerts`: 이 직무에 걸린 알림 + 기관 전체에 걸린 알림(`jobId` null), id 순. 판정 항목이 아닌 알림(기간·지원비 불일치 등)도 여기에는 보인다.
- `seniorNotes`: 최근 학기 먼저, 같은 학기는 쪽 순.
- `conditions.stipend.minWageRatio`는 소수 둘째 자리에서 반올림한다. 기준이 `UNSPECIFIED`거나 금액이 없으면 null.

**통근**(`POST /api/jobs/{jobId}/commute`, ADR-0007)
- 본문은 `{homeAreaCode}` 하나. 저장한 프로필이 있어도 프론트가 본문에 넣는다(프로필 값은 본문으로만). null이거나 빠지면 서경대에서 출발한다. `areas`에 없는 코드면 400 `INVALID_INPUT`.
- 서버가 카카오 대중교통 API(`GET https://dapi.kakao.com/v2/routing/publictraffic`, 헤더 `Authorization: KakaoAK ${KAKAO_REST_API_KEY}`)를 1번 부른다. 출발 = `area` 대표점(시·군·구청) 또는 서경대, 도착 = `workplace` 좌표(WGS84). 응답의 첫 경로에서 `minutes` = totalTime(초) ÷ 60 반올림, `transfers`, `fareWon` = fare.value.
- **저장하지 않는다.** 결과와 결과로 만든 값을 DB·캐시에 두지 않는다(카카오 운영정책: 결과 저장·가공 데이터 저장·미리 조회해 보관 금지). 같은 직무를 다시 열면 다시 부른다. 프론트는 그 화면에 있는 동안만 상태로 들고 있는다.
- 실패해도 **200 + `available: false`**, `minutes`·`transfers`·`fareWon`은 null. `unavailableReason`: `NO_WORKPLACE`(근무지 좌표 없음 — 카카오를 부르지 않는다) · `NO_ROUTE`(카카오 `NO_RESULTS`·`EQUAL_POINTS`·`STARTNODES_NULL`·`ENDNODES_NULL`) · `LIMITED`(호출 제한·하루 한도) · `PROVIDER_ERROR`(그 밖의 카카오 오류·3초 초과).
- 출발 시각은 정할 수 없다(카카오 API에 시각 값이 없다). 조회 시각에 따라 결과가 바뀌는지는 키를 받은 뒤 낮·밤에 한 번씩 불러 확인한다.
- 화면에는 '노원구에서 약 43분 · 환승 1회'처럼 출발지를 꼭 붙이고, 출처 '카카오맵 대중교통 기준'을 적는다. 로그에 `homeAreaCode`·좌표를 남기지 않는다.

**지망**
- 담기(`POST items`): 새로 담으면 201, 이미 담겨 있으면 200(그대로). 본문 없음. 현재 회차에 없는 직무는 400 `INVALID_INPUT`(`jobId`). 취소는 204, 없으면 404 `PLAN_ITEM_NOT_FOUND`.
- `GET /api/me/plan`·`PUT ranks` 응답의 `items` 순서: 순위 있는 것(1 → 3) 먼저, 그다음 담은 순.
- `PUT ranks`는 **순위 전체**를 보낸다. 여기 없는 담은 직무는 순위가 지워진다(`rank: null`). 순위는 1~3, 중복 불가, 한 직무에 하나, 3개까지, 담은 직무만 → 어기면 400 `RANK_INVALID`(아무것도 바꾸지 않음). `ranks`가 없거나 원소의 `jobId`·`rank`가 빠지면 400 `INVALID_INPUT`. `[]`이면 순위를 모두 지운다.
- `check`: 순위가 있는 직무의 신호와 대안을 준다(경고 문장 없음, ADR-0015). 대안은 요건이 맞는 빈 자리 — `INELIGIBLE`·`CLOSED`·남은 자리 0을 빼고, `fit` → 남은 자리 순으로 최대 5개.
- `asOf`는 회차 기간 안이어야 한다(아니면 400 `AS_OF_OUT_OF_RANGE`). 생략하면 `rounds/current`의 `replay.defaultAsOf`.

**센터** — CENTER만(아니면 403 `FORBIDDEN_ROLE`). 회차 직무 전부를 행으로 준다.
- `risks[].code`: `NARROW_POOL`(선호 전공 재학생 수가 적음 — 기준값은 시드 적재 뒤 분포를 보고 정한다) · `PORTFOLIO_REQUIRED` · `CERTIFICATE_REQUIRED` · `WEEKEND`(토·일 실습) · `DOC_ALERT`(검토 알림 있음).
- `historyAvailable`이 false면 `pastZeroRounds` 열을 숨긴다. 지난 회차 결과는 센터 동의 뒤에만 적재한다.

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
