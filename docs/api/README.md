# API 계약

프론트는 이 문서의 응답 예시로 목업을 만들고, 백엔드는 이 형태를 지킨다. 형태를 바꾸려면 이 문서를 먼저 고친다.
Notion API LIST는 이 문서의 사본이다. 둘이 다르면 이 문서가 맞다.

## 공통
- 기본 경로 `/api`, JSON, 필드는 camelCase, 날짜는 ISO 8601(`2026-07-18`, `2026-09-30T14:00:00+09:00`).
- **인증**: `Authorization: Bearer <accessToken>`. 쿠키는 쓰지 않는다 — Vercel과 Render는 사이트가 달라 서드파티 쿠키 차단에 걸린다.
  - 토큰은 JWT(HS256, 서명 키는 환경변수 `JWT_SECRET`). 만료는 가입 계정 7일, 체험 계정 24시간(계정도 그때 지운다).
  - 로그아웃은 프론트가 토큰을 버리는 것으로 끝난다(서버 호출 없음).
- **권한**: `공개` / `로그인`(역할 무관) / `STUDENT` / `CENTER`. 아래 목록의 '권한' 열.
- **오류**: HTTP 상태 코드 + `{"code": "...", "message": "사람이 읽는 설명"}`. 입력 검증 실패(`INVALID_INPUT`)만 `fields`를 더 준다. 코드 표는 맨 아래.
- **개인정보**
  - 프로필(학과·학년·평점 등)은 요청 **본문**으로만 보낸다. URL·쿼리에 넣지 않는다 — 그래서 판정·추천·지망 점검이 GET이 아니라 POST다.
  - 서버에 저장하는 건 `PUT /api/me/profile`에 `consent: true`로 보냈을 때뿐이다. 탈퇴하면 즉시 지운다.
  - 요청·응답 본문을 로그에 남기지 않는다.
- **가상 데이터**: 모집 신호는 모집기간 리플레이용 가상 데이터다. 신호를 주는 응답에는 `isVirtual`과 `signalSource`를 반드시 넣는다.
- **호출 제한**(초기값, 설정으로 조정): 체험 계정 만들기 IP당 1시간 20회 → 넘으면 429. 이유 문장(LLM) 계정당 1시간 30회·IP당 60회 → 넘으면 **200 + 기본 문장**(화면이 깨지지 않게).
- **코드값**은 영문 대문자이고 DB CHECK와 같은 집합이다(`V1__init.sql`). 화면 표기는 `GET /api/codes`에서 가져간다.
- **예시 값**: 기관·직무·인용문은 전부 가상이다(`(가상)` 표시). 실제 값은 시드에서 나온다. 목록 응답의 예시는 일부 행만 보여 준다.
- 구현할 때: `CorsConfig`의 허용 메서드에 `PUT`·`DELETE`를 더한다(지금은 GET·POST·OPTIONS). `Authorization` 헤더는 이미 허용돼 있다(`*`).

## 목록
| # | 구분 | 메서드 | 경로 | 권한 | 화면 | 설명 | 예시 |
|---|---|---|---|---|---|---|---|
| 1 | 공통 | GET | `/api/ping` | 공개 | — | 배포 연결 확인 | [응답](ping.json) |
| 2 | 공통 | GET | `/api/codes` | 공개 | 전체 | 코드값 → 화면 표기 | [응답](codes.json) |
| 3 | 인증 | POST | `/api/auth/signup` | 공개 | 가입 | 이메일 가입 | [요청](auth-signup.request.json) · [응답](auth-token.json) |
| 4 | 인증 | POST | `/api/auth/login` | 공개 | 로그인 | 이메일 로그인 | [요청](auth-login.request.json) · [응답](auth-token.json) |
| 5 | 인증 | POST | `/api/auth/guest` | 공개 | 시작 | 체험 계정 만들기([예시 프로필로 시작]·[센터 담당자로 보기]) | [요청](auth-guest.request.json) · [응답](auth-guest.json) |
| 6 | 내 정보 | GET | `/api/me` | 로그인 | 공통 | 내 계정 | [응답](me.json) |
| 7 | 내 정보 | DELETE | `/api/me` | 로그인 | 설정 | 탈퇴(계정·프로필·담은 지망 즉시 삭제) | 204 |
| 8 | 내 정보 | GET | `/api/me/profile` | STUDENT | S1 | 저장한 프로필 | [응답](me-profile.json) |
| 9 | 내 정보 | PUT | `/api/me/profile` | STUDENT | S1 | 프로필 저장(동의 필수) | [요청](me-profile.request.json) · [응답](me-profile.json) |
| 10 | 내 정보 | DELETE | `/api/me/profile` | STUDENT | S1 | 저장한 프로필만 삭제 | 204 |
| 11 | 기준 정보 | GET | `/api/departments` | 공개 | S1 | 학과 72개 | [응답](departments.json) |
| 12 | 기준 정보 | GET | `/api/rounds/current` | 공개 | S5·C4 | 현재 모집 회차와 리플레이 날짜 범위 | [응답](rounds-current.json) |
| 13 | 판정·추천 | POST | `/api/eligibility` | STUDENT | S2·S4 | 회차 직무 전부의 3층 판정 | [요청](profile-body.request.json) · [응답](eligibility.json) |
| 14 | 판정·추천 | POST | `/api/recommendations` | STUDENT | S3 | 적합도 추천 상위 5개 | [요청](profile-body.request.json) · [응답](recommendations.json) |
| 15 | 판정·추천 | POST | `/api/recommendations/{jobId}/reason` | STUDENT | S3 | 추천 이유 문장(LLM, 실패 시 기본 문장) | [요청](profile-body.request.json) · [응답](recommendation-reason.json) |
| 16 | 직무 | GET | `/api/jobs/{jobId}` | 로그인 | S4 | 직무 상세 + AI 추출 근거 | [응답](job-detail.json) |
| 17 | 지망 | GET | `/api/me/plan` | STUDENT | S3·S5 | 담은 직무와 순위 | [응답](me-plan.json) |
| 18 | 지망 | POST | `/api/me/plan/items` | STUDENT | S3 | 담기 | [요청](me-plan-items.request.json) · 201/200 |
| 19 | 지망 | DELETE | `/api/me/plan/items/{jobId}` | STUDENT | S3·S5 | 담기 취소 | 204 |
| 20 | 지망 | PUT | `/api/me/plan/ranks` | STUDENT | S5 | 1~3지망 순위 정하기 | [요청](me-plan-ranks.request.json) · [응답](me-plan.json) |
| 21 | 지망 | POST | `/api/me/plan/check` | STUDENT | S5 | 지망별 몰림 점검 + 빈 자리 제안 | [요청](me-plan-check.request.json) · [응답](me-plan-check.json) |
| 22 | 센터 | GET | `/api/center/board?asOf=` | CENTER | C4 | 모집 현황판 | [응답](center-board.json) |

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

**판정** — `verdict`는 `ELIGIBLE`(지원 가능) · `NEEDS_CHECK`(확인 필요) · `INELIGIBLE`(지원 불가).
- 이유 한 줄은 `{layer, item, requirement, mine, result, alertId?}`. `layer`는 `SCHOOL_RULE` · `INSTITUTION` · `MAJOR`, `result`는 `MET` · `NOT_MET` · `CHECK` · `INFO`.
- 정하는 순서: `SCHOOL_RULE`에 `NOT_MET`이 하나라도 있으면 `INELIGIBLE` → 아니면 `INSTITUTION`에 `NOT_MET`·`CHECK`가 하나라도 있으면 `NEEDS_CHECK` → 아니면 `ELIGIBLE`. `MAJOR`는 판정에 넣지 않는다(참고 표시).
- 문서 안에서 요건이 서로 다르게 적힌 항목(M2 검토 알림)은 `CHECK`이고 `alertId`가 붙는다.
- `majorMatch`는 `MATCH` · `NOT_LISTED` · `OPEN`(전공 무관).

**Stipend** — `{basis, amount, minWageRatio}`. `basis`가 `MONTHLY`면 월액, `HOURLY`면 시급(원). `minWageRatio`는 2026 최저임금(월 2,156,880원 / 시 10,320원) 대비 %, 소수 첫째 자리.

**Signal** — `{intent, interest, headcount, ratio, status, closesOn, closeReason, closesOnIsVirtual, expectedFullOn}`
- `intent`·`interest`는 asOf까지 누적한 지원 의사·관심 수. `ratio` = intent ÷ headcount(소수 둘째 자리).
- `status`: asOf가 회차 종료일보다 뒤이거나 `closesOn` ≤ asOf면 `CLOSED` → 아니면 ratio ≥ 1.0이면 `CROWDED` → 아니면 `OPEN`. **몰림은 마감이 아니다**(지원은 된다).
- `closeReason`: `APPLICATION_DEADLINE`(운영계획서 접수마감일자) · `CENTER_CLOSED`(센터 리스트 모집마감). `closesOnIsVirtual`이 true면 날짜가 생성기가 정한 가상 값이다.
- `expectedFullOn`: 정원 도달 예상일. 최근 3일 지원 의사 평균 증가량으로 외삽하고, 모집기간 안에 닿지 않거나 이미 닿았으면 null.

**Citation** — `{sourceType, documentTitle, page, quote}`. `sourceType`은 `OPERATION_PLAN` · `TESTIMONIAL`. 원문 PDF 링크는 주지 않는다.

**적합도** — `fit`은 `HIGH` · `MEDIUM`. 점수는 응답에 넣지 않는다(정렬에만 쓴다).

## 엔드포인트별 규칙
**인증**
- `signup`: 이메일은 소문자로 맞춰 저장, 비밀번호 8자 이상(BCrypt). 이메일 인증은 없다(MVP). 201 + 토큰. 이미 있으면 409 `EMAIL_TAKEN`.
- `login`: 틀리면 401 `LOGIN_FAILED`. 이메일과 비밀번호 중 무엇이 틀렸는지 말하지 않는다.
- `guest`: `role`은 `STUDENT` 또는 `CENTER`. `STUDENT`는 예시 프로필(메이크업디자인학과 3학년)이 저장된 체험 계정을 만들고 응답에 그 프로필을 준다(`isExample: true`). `CENTER`는 현황판용. 201 + 토큰. CENTER 역할은 이 경로와 시드로만 생기고 가입으로는 못 만든다.

**내 정보**
- `DELETE /api/me` → 204. 계정·프로필·담은 지망이 함께 지워진다(DB cascade).
- `PUT /api/me/profile`: `consent`가 true가 아니면 400 `CONSENT_REQUIRED`. `GET`에 저장한 게 없으면 404 `PROFILE_NOT_FOUND`.
- `hasProfile`(`/api/me`)이 false여도 판정·추천은 된다 — 프론트가 입력받은 프로필을 본문에 넣어 보내면 된다.

**판정·추천**
- `eligibility`: 회차 직무 **전부**(2026-2는 40행)를 돌려준다. S4의 '요건 ↔ 내 판정'도 이 응답의 해당 행을 쓴다(별도 API 없음).
- `recommendations`: `INELIGIBLE`을 뺀 직무 중 상위 5개. 이유는 `reasonTemplate`을 바로 주고 `reasonStatus: PENDING`이면 프론트가 카드마다 15번을 부른다. 0개면 `items: []`와 `blockedBy: [{item, count}]`(막은 요건별 직무 수).
- `reason`: `source`는 `LLM` · `CACHE` · `TEMPLATE`. LLM이 실패하거나 5초를 넘기거나 호출 제한에 걸려도 **200 + `TEMPLATE`**. 캐시는 메모리(프로필+직무+프롬프트 버전 해시)라 서버가 다시 뜨면 비워진다.

**직무** — 없으면 404 `JOB_NOT_FOUND`. `evidence`는 AI가 운영계획서에서 뽑은 값과 근거(허용 필드만), `seniorNotes`는 같은 기관의 선배 수기(이름·학과·학년 없음).

**지망**
- 담기(`POST items`): 새로 담으면 201, 이미 담겨 있으면 200(그대로). 취소는 204, 없으면 404 `PLAN_ITEM_NOT_FOUND`.
- `PUT ranks`는 **순위 전체**를 보낸다. 여기 없는 담은 직무는 순위가 지워진다(`rank: null`). 순위는 1~3, 중복 불가, 담은 직무만 → 어기면 400 `RANK_INVALID`.
- `check`: 순위가 있는 직무의 신호·경고와 대안을 준다. 대안은 요건이 맞는 빈 자리 — `INELIGIBLE`·`CLOSED`·남은 자리 0을 빼고, `fit` → 남은 자리 순으로 최대 5개.
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
