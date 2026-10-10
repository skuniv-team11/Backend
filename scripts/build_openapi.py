"""docs/api(README 목록 표 + 예시 JSON)로 Swagger UI가 읽는 OpenAPI 3.1 계약 스펙을 만든다.

    python scripts/build_openapi.py           # 스펙을 다시 만든다
    python scripts/build_openapi.py --check   # 파일이 최신인지·예시가 스키마에 맞는지만 본다(verify.sh)

출력: src/main/resources/static/openapi/contract.json → 서버가 /openapi/contract.json 으로 내보내고
Swagger UI(/swagger-ui.html)의 '계약' 탭이 읽는다(ADR-0011). 출력 파일은 손으로 고치지 않는다.

어디서 무엇을 가져오나
- 엔드포인트 목록·요약·권한·태그: docs/api/README.md '목록' 표
- 공통 규칙(info 설명)·오류 코드: README '공통'·'오류 코드' 절
- 응답·요청 예시: docs/api/*.json 그대로
- 코드값(enum): docs/api/codes.json(= V1 CHECK, check_api_docs.py가 맞춰 봄), 근거 필드명은 V1 DDL
- 필드 모양(타입·null 허용·범위): 이 파일의 SCHEMAS. null 허용은 V1 컬럼을 따른다
표준 라이브러리만 쓴다.
"""
import json, re, sys, pathlib, copy

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
ROOT = pathlib.Path(__file__).resolve().parent.parent
API = ROOT / "docs" / "api"
OUT = ROOT / "src" / "main" / "resources" / "static" / "openapi" / "contract.json"
README = (API / "README.md").read_text(encoding="utf-8")
DDL = (ROOT / "src" / "main" / "resources" / "db" / "migration" / "V1__init.sql").read_text(encoding="utf-8")
GRADLE = (ROOT / "build.gradle").read_text(encoding="utf-8")
EXAMPLES = {p.name: json.loads(p.read_text(encoding="utf-8")) for p in sorted(API.glob("*.json"))}
CODES = EXAMPLES["codes.json"]
REPO_DOC = "https://github.com/skuniv-team11/Backend/blob/develop/docs/api/README.md"

fails = []

# ───────────────────────── 스키마 작성 도구 ─────────────────────────

def R(name):
    return {"$ref": f"#/components/schemas/{name}"}

def nul(s):
    """null 허용. $ref는 anyOf로 감싼다."""
    if "$ref" in s:
        return {"anyOf": [s, {"type": "null"}]}
    s = dict(s)
    t = s["type"]
    s["type"] = [t, "null"] if isinstance(t, str) else [*t, "null"]
    if "enum" in s:
        s["enum"] = [*s["enum"], None]
    return s

def d(s, text):
    """설명을 붙인다. $ref 옆 설명은 3.1에서 허용된다."""
    return {**s, "description": text}

STR = {"type": "string"}
INT = {"type": "integer"}
NUM = {"type": "number"}
BOOL = {"type": "boolean"}
DATE = {"type": "string", "format": "date"}
DATETIME = {"type": "string", "format": "date-time"}
ID = {"type": "integer", "minimum": 1}
PAGE = {"type": "integer", "minimum": 1}
LOGO_PATH = d(nul(STR), "기관 로고 이미지 경로(API 서버 기준, 예: /logos/3.png). API 기본 주소 뒤에 붙여 <img>로 띄운다. 로고가 없으면 null → 기관명 첫 글자로 대신(ADR-0019)")

def arr(items, **kw):
    return {"type": "array", "items": items, **kw}

def obj(props, optional=(), desc=None):
    s = {"type": "object", "properties": props, "required": [k for k in props if k not in optional]}
    if desc:
        s["description"] = desc
    return s

def code_enum(key):
    """codes.json의 코드 묶음 하나를 enum 스키마로. 설명에 화면 표기를 붙인다."""
    labels = CODES[key]
    return {"type": "string", "enum": list(labels),
            "description": " · ".join(f"`{k}` {v}" for k, v in labels.items())}

def enum_name(key):
    return key[0].upper() + key[1:]

def ddl_field_keys():
    m = re.search(r"CONSTRAINT field_evidence_allowed_key CHECK \((.*?)\n\s*\)\n\);", DDL, re.S)
    return sorted(set(re.findall(r"'([A-Za-z]+)'", m.group(1))))

# ───────────────────────── 스키마 ─────────────────────────

S = {enum_name(k): code_enum(k) for k in CODES}
S["NtsStatus"] = d(S["NtsStatus"], "국세청 사업자 상태(오프라인 조회 결과). " + S["NtsStatus"]["description"])
S["EvidenceFieldKey"] = {"type": "string", "enum": ddl_field_keys(),
                         "description": "근거를 보여 줄 수 있는 추출 필드명(V1 field_evidence 허용 목록). 사업자번호·매출액 등은 없다"}

ALTERNATIVES_TEXT = ("verdict ELIGIBLE · CLOSED 아님 · 남은 자리(headcount − interest) > 0 · 이미 담은 직무 아님. "
                     "적합도 점수 → 남은 자리 순 최대 5개")

AREA_CODE = {"type": "string", "pattern": r"^(11|28|41)\d{3}$",
             "description": "시·군·구 5자리(서울 11·인천 28·경기 41). `GET /api/areas`의 code"}

CERTIFICATE_CODE = {"type": "string", "pattern": r"^[A-Z][A-Z0-9_]*$", "maxLength": 40,
                    "description": "`GET /api/certificates`의 code"}

PROFILE_PROPS = {
    "departmentId": d(ID, "`GET /api/departments`의 id"),
    "grade": {"type": "integer", "minimum": 1, "maximum": 4},
    "completedSemesters": {"type": "integer", "minimum": 0, "maximum": 8},
    "gpa": {"type": "number", "minimum": 0, "maximum": 4.5, "description": "소수 첫째 자리까지"},
    "graduationExpected": d(BOOL, "다음 졸업(2026-2 회차 → 2027년 2월) 예정이면 true"),
    "interestText": nul({"type": "string", "maxLength": 200, "description": "적합도 추천의 질의. 200자 이하"}),
    "homeAreaCode": d(nul(AREA_CODE), "사는 곳(선택). null이면 통근을 서경대에서 출발로 본다. 통근 조회에만 쓴다"),
    "certificates": d(nul(arr(CERTIFICATE_CODE, maxItems=20)),
                      "가진 자격증(선택, ADR-0021). []이면 없음, null이거나 빠지면 답하지 않음(자격증 줄이 '직접 확인'). "
                      "판정의 자격증 줄에만 쓴다"),
}
PROFILE_OPTIONAL = ("interestText", "homeAreaCode", "certificates")
PROFILE_VIEW_PROPS = {
    **PROFILE_PROPS,
    "department": R("DepartmentRef"),
    "homeArea": nul(R("Area")),
    "isExample": d(BOOL, "체험 계정의 예시 프로필이면 true"),
}

SIGNAL = obj({
    "interest": d({"type": "integer", "minimum": 0},
                  "관심 = asOf까지 누적한 내 지망에 담은 사람 수(리플레이 가상 값 + 실제 담은 수, ADR-0019)"),
    "liveInterest": d({"type": "integer", "minimum": 0},
                      "interest 중 실제 사용자가 담은 수(순위 무관, 1인 1표, 체험 계정 포함). 지망 점검은 본인 제외. "
                      "replay.defaultAsOf에 생긴 관심으로 더하므로 asOf가 그보다 앞이거나 그날 마감된 직무면 0"),
    "headcount": {"type": "integer", "minimum": 1},
    "ratio": d({"type": "number", "minimum": 0}, "interest ÷ headcount, 소수 둘째 자리"),
    "status": R("SignalStatus"),
    "closesOn": nul(DATE),
    "closeReason": nul(R("CloseReason")),
    "closesOnIsVirtual": d(BOOL, "true면 closesOn이 생성기가 정한 가상 날짜"),
    "expectedFullOn": d(nul(DATE), "정원 도달 예상일. 모집기간 안에 닿지 않거나 이미 닿았으면 null"),
}, desc="모집 신호(관심 = 내 지망에 담은 사람 수). status: asOf가 회차 종료일보다 뒤이거나 closesOn ≤ asOf면 CLOSED → 아니면 OPEN. 관심이 정원을 넘어도 몰림 표시·경고는 하지 않는다(ADR-0015)")

CLOSING = obj({"closesOn": d(nul(DATE), "이 날부터 지원 불가. 화면은 하루 전 날짜를 마감일로 보여 준다"),
               "closeReason": nul(R("CloseReason")), "closesOnIsVirtual": BOOL},
              desc="직무의 모집마감(기준일과 상관없이 고정). 마감이 없으면 closesOn·closeReason이 null")

S.update({
    "Error": obj({
        "code": R("ErrorCode"),
        "message": d(STR, "사람이 읽는 설명(예시 문구는 서버 문구와 다를 수 있다)"),
        "fields": d(arr(obj({"field": STR, "reason": STR})), "INVALID_INPUT일 때만"),
    }, optional=("fields",)),
    "Ping": obj({"status": STR, "service": STR, "time": DATETIME}),
    "Codes": {"type": "object", "description": "코드 묶음 이름 → {코드값: 화면 표기}. 값 집합은 각 enum 스키마와 같다",
              "additionalProperties": {"type": "object", "additionalProperties": STR}},
    "Credentials": obj({
        "email": {"type": "string", "format": "email", "description": "소문자로 맞춰 저장"},
        "password": {"type": "string", "minLength": 8, "description": "8자 이상, UTF-8 72바이트 이하(BCrypt 한도)"},
    }),
    "GuestRequest": obj({"role": R("Role")}),
    "User": obj({
        "id": ID,
        "email": d(nul({"type": "string", "format": "email"}), "체험 계정은 null"),
        "role": R("Role"),
        "isGuest": BOOL,
        "expiresAt": d(nul(DATETIME), "체험 계정이 지워지는 시각. 가입 계정은 null"),
        "hasProfile": d(BOOL, "false여도 판정·추천은 된다(프로필을 본문에 넣어 보냄)"),
    }),
    "AuthToken": obj({
        "accessToken": d(STR, "JWT(HS256). `Authorization: Bearer <accessToken>`"),
        "tokenType": {"type": "string", "enum": ["Bearer"]},
        "expiresAt": d(DATETIME, "가입 계정 7일, 체험 계정 24시간"),
        "user": R("User"),
    }),
    "GuestAuthToken": obj({
        "accessToken": d(STR, "JWT(HS256). `Authorization: Bearer <accessToken>`"),
        "tokenType": {"type": "string", "enum": ["Bearer"]},
        "expiresAt": d(DATETIME, "체험 계정 24시간"),
        "user": R("User"),
        "profile": d(nul(R("ProfileView")), "STUDENT면 저장된 예시 프로필(isExample: true). CENTER, 또는 학과 시드 전의 STUDENT면 null"),
    }, optional=("profile",)),
    "Profile": obj(PROFILE_PROPS, optional=PROFILE_OPTIONAL,
                   desc="학생 프로필. 요청 본문으로만 보낸다(URL·쿼리에 넣지 않는다)"),
    "ProfileBody": obj({"profile": R("Profile")}),
    "ProfileSaveRequest": obj({
        **PROFILE_PROPS,
        "consent": d(BOOL, "true가 아니면 400 CONSENT_REQUIRED"),
    }, optional=PROFILE_OPTIONAL),
    "ProfileView": obj(PROFILE_VIEW_PROPS, optional=PROFILE_OPTIONAL),
    "SavedProfile": obj({**PROFILE_VIEW_PROPS, "consentedAt": DATETIME, "updatedAt": DATETIME},
                        optional=PROFILE_OPTIONAL),
    "DepartmentRef": obj({"id": ID, "name": STR}),
    "Department": obj({"id": ID, "name": STR, "college": nul(STR)}),
    "Departments": obj({"departments": arr(R("Department"))}),
    "Area": obj({"code": AREA_CODE, "sido": STR, "name": STR}),
    "Areas": obj({"areas": arr(R("Area"))}),
    "Certificate": obj({"code": CERTIFICATE_CODE, "label": STR}),
    "Certificates": d(obj({"certificates": arr(R("Certificate"))}),
                      "이번 회차 직무가 요구·우대하는 자격증만, 코드표 순서(ADR-0021)"),
    "RoundRef": obj({"id": ID, "termCode": {"type": "string", "pattern": r"^\d{4}-[12]$"}}),
    "CurrentRound": obj({
        "id": ID,
        "programName": STR,
        "termCode": {"type": "string", "pattern": r"^\d{4}-[12]$"},
        "roundNo": ID,
        "recruitStart": DATE,
        "recruitEnd": DATE,
        "replay": obj({
            "defaultAsOf": d(DATE, "asOf를 생략하면 이 날짜"),
            "minDate": DATE,
            "maxDate": DATE,
            "signalsAreVirtual": BOOL,
        }),
    }),
    "InstitutionRef": obj({"id": ID, "name": STR, "logoPath": LOGO_PATH}),
    "ReasonLine": obj({
        "layer": R("ReasonLayer"),
        "item": STR,
        "requirement": STR,
        "mine": STR,
        "result": R("ReasonResult"),
        "alertId": d(ID, "검토 알림(M2)의 fieldKey가 판정 항목(gradeRequirement·gpaRequirement·portfolio·certificate)이면 그 항목 행(CHECK)에 붙는다"),
        "citation": d(R("ReasonCitation"), "그 행의 판정이 쓴 요건 원문(ADR-0023). 학교 규정 = 학생 모집안내, "
                                             "학년·학점·포트폴리오·선호 전공 = 참여기관 리스트 칸, 자격증 = 운영계획서. "
                                             "알림으로 새로 만든 행이나 출처를 못 찾은 행에는 필드가 없다"),
    }, optional=("alertId", "citation")),
    "ReasonCitation": obj({"sourceType": R("SourceType"), "documentTitle": STR,
                           "page": d(nul(PAGE), "쪽이 없는 문서(참여기관 리스트 엑셀, 모집안내 웹 공지)면 null"),
                           "quote": d(STR, "원문 그대로(앞의 글머리표만 뗌)")},
                          desc="판정 이유 줄의 출처(Citation과 같은 모양). 원문 PDF 링크는 주지 않는다"),
    "EligibilityJob": obj({
        "jobId": ID, "title": STR, "team": STR,
        "institution": R("InstitutionRef"),
        "verdict": R("Verdict"),
        "majorMatch": R("MajorMatch"),
        "closing": d(CLOSING, "직무 상세의 closing과 같은 값. 목록은 closesOn ≤ 기준일이면 '마감' 꼬리표를 단다"),
        "alertCount": d({"type": "integer", "minimum": 0}, "그 직무에 걸린 검토 알림 수(jobId가 그 직무인 것만, 기관 단위 알림은 세지 않는다). '문서 검토' 꼬리표"),
        "reasons": arr(R("ReasonLine")),
    }),
    "Eligibility": obj({
        "round": R("RoundRef"),
        "summary": obj({"total": INT, "eligible": INT, "needsCheck": INT, "ineligible": INT}),
        "jobs": d(arr(R("EligibilityJob")), "회차 직무 전부"),
    }),
    "Stipend": obj({
        "basis": R("StipendBasis"),
        "amount": d(nul({"type": "integer", "minimum": 1}), "원. MONTHLY면 월액, HOURLY면 시급"),
        "minWageRatio": d(nul(NUM), "2026 최저임금(월 2,156,880원 / 시 10,320원) 대비 %, 소수 첫째 자리"),
    }),
    "Citation": obj({"sourceType": R("SourceType"), "documentTitle": STR, "page": PAGE, "quote": STR},
                    desc="근거 인용. 원문 PDF 링크는 주지 않는다"),
    "Recommendation": obj({
        "rank": {"type": "integer", "minimum": 1, "maximum": 5},
        "jobId": ID, "title": STR,
        "institution": R("InstitutionRef"),
        "verdict": R("Verdict"),
        "fit": R("Fit"),
        "jobType": R("JobType"),
        "stipend": R("Stipend"),
        "reasonTemplate": d(STR, "바로 보여 줄 기본 이유 문장"),
        "reasonStatus": d(STR, "PENDING이면 카드마다 `POST /api/recommendations/{jobId}/reason`을 부른다"),
        "citations": arr(R("Citation")),
    }),
    "Recommendations": obj({
        "round": R("RoundRef"),
        "items": d(arr(R("Recommendation"), maxItems=5),
                   "INELIGIBLE과 기준일에 마감된 직무를 빼고, 선호 전공이 맞거나 관심 문장과 겹치는 직무만 적합도 점수 순으로 5개까지"
                   "(0~5개, ADR-0022). 점수는 주지 않는다"),
        "blockedBy": d(arr(obj({"item": STR, "count": INT})),
                       "items가 비었을 때 막은 요건별 직무 수(학교 규정 항목 · '자격증' · '모집 마감' · '관심 분야'). 1~4개면 []"),
    }),
    "RecommendationReason": obj({
        "jobId": ID,
        "source": R("ReasonSource"),
        "text": STR,
        "citations": arr(R("Citation")),
    }, desc="LLM 실패·5초 초과·호출 제한이어도 200 + source TEMPLATE"),
    "Alert": obj({
        "id": ID,
        "institution": R("InstitutionRef"),
        "jobId": nul(ID),
        "kind": R("AlertKind"),
        "fieldKey": nul(STR),
        "description": STR,
        "documentTitle": d(nul(STR), "pageA·quoteA(·pageB·quoteB)가 있는 문서 이름(예: 소서 운영계획서). 문서가 없으면 null(ADR-0023)"),
        "pageA": nul(PAGE), "quoteA": nul(STR),
        "pageB": nul(PAGE), "quoteB": nul(STR),
    }, desc="M2 검토 알림"),
    "JobDetail": obj({
        "id": ID,
        "round": R("RoundRef"),
        "institution": obj({
            "id": ID, "name": STR, "logoPath": LOGO_PATH,
            "size": R("Size"), "listing": R("Listing"),
            "businessType": nul(STR), "businessItem": nul(STR), "address": nul(STR),
            "ntsStatus": nul(R("NtsStatus")), "ntsCheckedOn": nul(DATE),
        }),
        "team": STR,
        "title": STR,
        "overview": nul(STR),
        "educationGoal": nul(STR),
        "competencies": nul(STR),
        "weeklyPlan": arr(obj({"seq": ID, "weeksLabel": STR, "content": STR})),
        "conditions": obj({
            "course": R("Course"),
            "jobType": R("JobType"),
            "period": obj({"start": nul(DATE), "end": nul(DATE)}),
            "workHoursText": nul(STR),
            "weeklyHours": nul({"type": "number", "exclusiveMinimum": 0}),
            "weekdays": arr(R("Weekday")),
            "overtime": R("Overtime"),
            "laborContract": nul(BOOL),
            "stipend": R("Stipend"),
            "benefits": arr(R("Benefit")),
            "headcount": {"type": "integer", "minimum": 1},
        }),
        "requirements": obj({
            "gradeRule": R("GradeRule"),
            "gpaMin": nul({"type": "number", "minimum": 0, "maximum": 4.5}),
            "portfolio": R("Requirement"),
            "certificate": R("Requirement"),
            "certificateText": nul(STR),
            "majorText": d(nul(STR), "선호 전공 원문(표시용). 자격 조건이 아니다"),
            "majorOpen": d(BOOL, "전공 무관이면 true"),
            "majorAliases": d(arr(obj({
                "label": d(STR, "선호 전공 표기(job_major_alias)"),
                "departments": d(arr(R("DepartmentRef")), "확정된 학과 대응(major_alias_department, EXACT·CONFIRMED만)"),
            })), "선호 전공 표기 → 학과. majorOpen이면 []. '선호 전공 안내'와 판정 이유의 '표기 해석'에 쓴다"),
        }),
        "workplace": d(nul(obj({
            "address": STR,
            "hasCoordinates": d(BOOL, "true면 프론트가 `POST /api/jobs/{jobId}/commute`를 부른다"),
        })), "근로지. V1 job.workplace_id가 null을 허용해 null일 수 있다"),
        "closing": CLOSING,
        "evidence": d(arr(obj({
            "fieldKey": R("EvidenceFieldKey"),
            "label": STR,
            "rawValue": d(STR, "문서에 적힌 그대로"),
            "documentTitle": STR,
            "page": PAGE,
            "quote": {"type": "string", "maxLength": 200},
        })), "AI가 운영계획서에서 뽑은 값과 근거(허용 필드만)"),
        "alerts": arr(R("Alert")),
        "seniorNotes": d(arr(obj({
            "termCode": {"type": "string", "pattern": r"^\d{4}-[12]$"},
            "teamText": nul(STR),
            "documentTitle": STR,
            "page": PAGE,
            "major": d(nul(STR), "수기에 적힌 학과(전공) 원문"),
            "grade": d(nul(STR), "수기에 적힌 학년 원문(예: 4학년)"),
            "oneLine": d(nul(STR), "한 줄 소개(수기 제목)"),
            "companyIntro": d(nul(STR), "수기의 기관·부서 소개 문단"),
            "activities": arr(STR, minItems=1),
            "outcomes": d(arr(STR, maxItems=3),
                          "실습 결과 중 원문 그대로 자른 사실 구절 0~3개(추천 근거용, ADR-0020)"),
            "results": d(nul(STR), "실습 결과 문단 전문"),
            "reflection": d(nul(STR), "소감 문단 전문"),
        })), "같은 기관의 선배 수기 전문(ADR-0030). 이름·사진은 없다. 전부 '우수' 수기라 화면에 그 점을 밝힌다"),
        "photos": d(arr(obj({
            "seq": ID,
            "path": d(STR, "사진 경로(API 서버 기준, 예: /photos/7/1.jpg). API 기본 주소 뒤에 붙여 <img>로 띄운다"),
            "caption": d(nul(STR), "사진 아래에 인쇄된 설명 원문. 없으면 null"),
            "documentTitle": d(STR, "사진이 실린 문서(예: 소서 실습기관 소개서)"),
            "page": PAGE,
            "width": {"type": "integer", "minimum": 1},
            "height": {"type": "integer", "minimum": 1},
        })), "그 기관 실습기관 소개서의 '회사 전경 및 활동사진'(ADR-0030). 순번 순. 소개서에 사진 칸이 없으면 []"),
    }, desc="통근 시간은 이 응답에 없다(통근 조회를 따로 부른다)"),
    "CommuteRequest": obj({"homeAreaCode": d(nul(AREA_CODE), "null이거나 빠지면 서경대에서 출발")},
                          optional=("homeAreaCode",)),
    "Commute": obj({
        "jobId": ID,
        "available": BOOL,
        "origin": obj({"type": R("CommuteOrigin"), "areaCode": nul(AREA_CODE), "label": STR}),
        "destination": obj({"address": STR}),
        "minutes": nul({"type": "integer", "minimum": 1}),
        "transfers": nul({"type": "integer", "minimum": 0}),
        "fareWon": nul({"type": "integer", "minimum": 0}),
        "provider": R("CommuteProvider"),
        "queriedAt": DATETIME,
        "unavailableReason": nul(R("CommuteUnavailable")),
    }, desc="카카오 대중교통 실시간 결과. 저장하지 않는다. 실패해도 200 + available false(minutes·transfers·fareWon null)"),
    "PlanItem": obj({
        "jobId": ID, "title": STR,
        "institution": R("InstitutionRef"),
        "rank": d(nul({"type": "integer", "minimum": 1, "maximum": 3}), "1~3지망. 순위를 안 정했으면 null"),
        "addedAt": DATETIME,
    }),
    "Plan": obj({"items": arr(R("PlanItem"))}),
    "PlanAddRequest": obj({"jobId": ID}),
    "PlanRanksRequest": obj({
        "ranks": d(arr(obj({"jobId": ID, "rank": {"type": "integer", "minimum": 1, "maximum": 3}}), maxItems=3),
                   "순위 전체. 여기 없는 담은 직무는 순위가 지워진다. 1~3, 중복 불가, 담은 직무만"),
    }),
    "PlanCheckRequest": obj({
        "profile": R("Profile"),
        "asOf": d(DATE, "회차 모집기간 안. 생략하면 rounds/current의 replay.defaultAsOf"),
    }, optional=("asOf",)),
    "Signal": SIGNAL,
    "PlanCheck": obj({
        "asOf": DATE,
        "isVirtual": BOOL,
        "signalSource": R("SignalSource"),
        "items": arr(obj({
            "rank": {"type": "integer", "minimum": 1, "maximum": 3},
            "jobId": ID, "title": STR,
            "institution": R("InstitutionRef"),
            "signal": R("Signal"),
        })),
        "alternatives": d(arr(R("Alternative"), maxItems=5), ALTERNATIVES_TEXT),
    }),
    "Alternative": obj({
        "jobId": ID, "title": STR,
        "institution": R("InstitutionRef"),
        "verdict": R("Verdict"),
        "fit": R("Fit"),
        "remaining": {"type": "integer", "minimum": 1},
        "signal": R("Signal"),
        "why": d(STR, "규칙 문장. 기준 직무와 같은 기관이면 지망 점검은 '1지망과 같은 기관의 직무이고', 담은 직무 기준(#27)은 "
                      "'방금 담은 직무와 같은 기관의 직무이고', 관심 문장과 겹치면 '관심 분야와 가깝고', 둘 다 아니면 "
                      "'지원 조건을 모두 통과했고' + 관심 0이면 '지금 담은 사람이 0명이에요.', 아니면 '남은 자리가 N개예요.'"),
    }, desc="요건이 맞는 빈 자리(ADR-0016). 지망 점검(#23)과 담은 직무 기준 빈 자리(#27)가 같은 모양을 쓴다"),
    "PlanItemAlternatives": obj({
        "asOf": DATE,
        "isVirtual": BOOL,
        "signalSource": R("SignalSource"),
        "item": d(obj({
            "jobId": ID, "title": STR,
            "institution": R("InstitutionRef"),
            "rank": d(nul({"type": "integer", "minimum": 1, "maximum": 3}), "1~3지망. 순위를 안 정했으면 null"),
            "signal": d(R("Signal"), "관심은 본인을 뺀 다른 사람 수(지망 점검과 같다)"),
        }), "기준이 된 담은 직무"),
        "alternatives": d(arr(R("Alternative"), maxItems=5), ALTERNATIVES_TEXT + ". 기준 직무도 빠진다"),
    }, desc="[담기] 바로 뒤에 부른다(ADR-0029). 제안을 띄울지는 화면이 item.signal.interest ≥ headcount로 정한다 — 몰림 상태는 없다(ADR-0015)"),
    "CenterBoard": obj({
        "asOf": DATE,
        "isVirtual": BOOL,
        "signalSource": R("SignalSource"),
        "round": R("RoundRef"),
        "summary": obj({"jobs": INT, "seats": INT,
                        "interestTotal": d(INT, "asOf까지 관심 합(가상 + 실제)"),
                        "liveInterestTotal": d(INT, "interestTotal 중 실제 사용자가 담은 수"),
                        "zeroSignalJobs": d(INT, "관심이 0인 직무 수"), "closedJobs": INT}),
        "historyAvailable": d(BOOL, "false면 pastZeroRounds 열을 숨긴다"),
        "rows": d(arr(obj({
            "jobId": ID,
            "institution": R("InstitutionRef"),
            "title": STR,
            "headcount": {"type": "integer", "minimum": 1},
            "signal": R("Signal"),
            "eligiblePool": d({"type": "integer", "minimum": 0}, "적격 학생 풀(선호 전공 재학생 수). 200명 미만이면 NARROW_POOL"),
            "risks": arr(obj({"code": R("Risk"), "label": STR, "detail": nul(STR)})),
            "alertCount": {"type": "integer", "minimum": 0},
            "pastZeroRounds": d(arr({}), "지난 회차 0명 이력. 원소 모양은 지난 회차 결과를 적재할 때 정한다(지금 예시는 빈 배열)"),
        })), "회차 직무 전부"),
        "alerts": arr(R("Alert")),
    }),
    "JobViews": obj({
        "jobId": ID,
        "views": d({"type": "integer", "minimum": 0}, "지금까지 조회 수. 학생 계정마다 직무별로 하루(한국 시간) 한 번 센다"),
        "todayViews": d({"type": "integer", "minimum": 0}, "오늘(한국 시간) 조회 수"),
    }, desc="직무 조회수(ADR-0019). 실제 값만(가상 값 없음). 조회는 직무 상세(GET /api/jobs/{jobId})를 학생이 열 때만 센다"),
})

# 오류 코드 표(README) → ErrorCode enum과 HTTP 상태
ERR = {}
for code, http, when in re.findall(r"^\| `([A-Z_]+)` \| (\d{3}) \| (.+?) \|$", README.split("## 오류 코드", 1)[1], re.M):
    ERR[code] = (int(http), when.strip())
S["ErrorCode"] = {"type": "string", "enum": list(ERR),
                  "description": " · ".join(f"`{c}` {h}" for c, (h, _) in ERR.items()),
                  "x-status": {c: h for c, (h, _) in ERR.items()}}  # 서버 ErrorCode enum과 대조(ContractTest)

# ───────────────────────── 엔드포인트 ─────────────────────────
# req: (스키마, [예시 파일]) / ok: {상태: (스키마 또는 None, [예시 파일])} / errors: 공통 규칙 밖에서 더 나는 오류 코드
# 공통 규칙: 본문이 있으면 INVALID_INPUT, 공개가 아니면 AUTH_REQUIRED·TOKEN_EXPIRED, STUDENT·CENTER면 FORBIDDEN_ROLE
PB = ("ProfileBody", ["profile-body.request.json"])
ENDPOINTS = {
    "GET /api/ping": dict(op="ping", ok={200: ("Ping", ["ping.json"])}),
    "GET /api/codes": dict(op="getCodes", ok={200: ("Codes", ["codes.json"])}),
    "POST /api/auth/signup": dict(op="signup", req=("Credentials", ["auth-signup.request.json"]),
                                  ok={201: ("AuthToken", ["auth-token.json"])}, errors=["EMAIL_TAKEN"]),
    "POST /api/auth/login": dict(op="login", req=("Credentials", ["auth-login.request.json"]),
                                 ok={200: ("AuthToken", ["auth-token.json"])}, errors=["LOGIN_FAILED"]),
    "POST /api/auth/guest": dict(op="createGuest", req=("GuestRequest", ["auth-guest.request.json"]),
                                 ok={201: ("GuestAuthToken", ["auth-guest.json"])}, errors=["RATE_LIMITED"]),
    "GET /api/me": dict(op="getMe", ok={200: ("User", ["me.json"])}),
    "DELETE /api/me": dict(op="deleteMe", ok={204: (None, [])}),
    "GET /api/me/profile": dict(op="getMyProfile", ok={200: ("SavedProfile", ["me-profile.json"])},
                                errors=["PROFILE_NOT_FOUND"]),
    "PUT /api/me/profile": dict(op="saveMyProfile", req=("ProfileSaveRequest", ["me-profile.request.json"]),
                                ok={200: ("SavedProfile", ["me-profile.json"])}, errors=["CONSENT_REQUIRED"]),
    "DELETE /api/me/profile": dict(op="deleteMyProfile", ok={204: (None, [])}),
    "GET /api/departments": dict(op="getDepartments", ok={200: ("Departments", ["departments.json"])}),
    "GET /api/areas": dict(op="getAreas", ok={200: ("Areas", ["areas.json"])}),
    "GET /api/rounds/current": dict(op="getCurrentRound", ok={200: ("CurrentRound", ["rounds-current.json"])}),
    "POST /api/eligibility": dict(op="checkEligibility", req=PB, ok={200: ("Eligibility", ["eligibility.json"])}),
    "POST /api/recommendations": dict(op="getRecommendations", req=PB,
                                      ok={200: ("Recommendations", ["recommendations.json"])}),
    "POST /api/recommendations/{jobId}/reason": dict(op="getRecommendationReason", req=PB,
                                                     ok={200: ("RecommendationReason", ["recommendation-reason.json"])},
                                                     errors=["JOB_NOT_FOUND"]),
    "GET /api/jobs/{jobId}": dict(op="getJob", ok={200: ("JobDetail", ["job-detail.json"])}, errors=["JOB_NOT_FOUND"]),
    "POST /api/jobs/{jobId}/commute": dict(op="getCommute", req=("CommuteRequest", ["commute.request.json"]),
                                           ok={200: ("Commute", ["commute.json", "commute-unavailable.json"])},
                                           errors=["JOB_NOT_FOUND"]),
    "GET /api/me/plan": dict(op="getMyPlan", ok={200: ("Plan", ["me-plan.json"])}),
    "POST /api/me/plan/items": dict(op="addPlanItem", req=("PlanAddRequest", ["me-plan-items.request.json"]),
                                    ok={201: (None, []), 200: (None, [])}),
    "DELETE /api/me/plan/items/{jobId}": dict(op="removePlanItem", ok={204: (None, [])}, errors=["PLAN_ITEM_NOT_FOUND"]),
    "PUT /api/me/plan/ranks": dict(op="setPlanRanks", req=("PlanRanksRequest", ["me-plan-ranks.request.json"]),
                                   ok={200: ("Plan", ["me-plan.json"])}, errors=["RANK_INVALID"]),
    "POST /api/me/plan/check": dict(op="checkPlan", req=("PlanCheckRequest", ["me-plan-check.request.json"]),
                                    ok={200: ("PlanCheck", ["me-plan-check.json"])}, errors=["AS_OF_OUT_OF_RANGE"]),
    "GET /api/center/board": dict(op="getCenterBoard", ok={200: ("CenterBoard", ["center-board.json"])},
                                  errors=["AS_OF_OUT_OF_RANGE"]),
    "GET /api/jobs/{jobId}/views": dict(op="getJobViews", ok={200: ("JobViews", ["job-views.json"])},
                                        errors=["JOB_NOT_FOUND"]),
    "GET /api/certificates": dict(op="getCertificates", ok={200: ("Certificates", ["certificates.json"])}),
    "POST /api/me/plan/items/{jobId}/alternatives": dict(
        op="getPlanItemAlternatives", req=("PlanCheckRequest", ["me-plan-check.request.json"]),
        ok={200: ("PlanItemAlternatives", ["me-plan-item-alternatives.json"])},
        errors=["PLAN_ITEM_NOT_FOUND", "AS_OF_OUT_OF_RANGE"]),
}
OK_TEXT = {200: "성공", 201: "만들었음", 204: "본문 없음"}
STATUS_TEXT = {200: "이미 담겨 있음(그대로)", 201: "새로 담음"}  # POST /api/me/plan/items

# ───────────────────────── 예시 검증(표준 라이브러리 미니 검증기) ─────────────────────────
# 예시에 스키마에 없는 필드가 있으면 실패로 본다(닫힌 객체) — 예시와 스키마가 갈라지는 걸 잡으려고.

FORMATS = {
    "date": r"^\d{4}-\d{2}-\d{2}$",
    "date-time": r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?(Z|[+-]\d{2}:\d{2})$",
    "email": r"^[^@\s]+@[^@\s]+\.[^@\s]+$",
}
PY_TYPES = {"string": str, "integer": int, "number": (int, float), "boolean": bool,
            "object": dict, "array": list, "null": type(None)}

def type_ok(v, t):
    if t in ("integer", "number") and isinstance(v, bool):
        return False
    if t == "integer":
        return isinstance(v, int)  # 정수 필드에 1.0을 쓰지 않는다
    return isinstance(v, PY_TYPES[t])

def validate(v, s, path, errs):
    if "$ref" in s:
        s = {**S[s["$ref"].rsplit("/", 1)[1]], **{k: x for k, x in s.items() if k != "$ref"}}
    if "anyOf" in s:
        if not any(not _errs(v, sub) for sub in s["anyOf"]):
            errs.append(f"{path}: anyOf 어느 쪽에도 맞지 않음 ({json.dumps(v, ensure_ascii=False)[:60]})")
        return
    if "type" in s:
        ts = s["type"] if isinstance(s["type"], list) else [s["type"]]
        if not any(type_ok(v, t) for t in ts):
            errs.append(f"{path}: 타입 {ts} 아님 ({json.dumps(v, ensure_ascii=False)[:60]})")
            return
    if "enum" in s and v not in s["enum"]:
        errs.append(f"{path}: {v!r} 가 enum에 없음")
    if v is None:
        return
    if isinstance(v, str):
        if "minLength" in s and len(v) < s["minLength"]:
            errs.append(f"{path}: {s['minLength']}자보다 짧음")
        if "maxLength" in s and len(v) > s["maxLength"]:
            errs.append(f"{path}: {s['maxLength']}자보다 김")
        if "pattern" in s and not re.search(s["pattern"], v):
            errs.append(f"{path}: 형식 {s['pattern']} 아님 ({v})")
        if s.get("format") in FORMATS and not re.match(FORMATS[s["format"]], v):
            errs.append(f"{path}: {s['format']} 형식 아님 ({v})")
    if isinstance(v, (int, float)) and not isinstance(v, bool):
        if "minimum" in s and v < s["minimum"]:
            errs.append(f"{path}: {v} < {s['minimum']}")
        if "maximum" in s and v > s["maximum"]:
            errs.append(f"{path}: {v} > {s['maximum']}")
        if "exclusiveMinimum" in s and v <= s["exclusiveMinimum"]:
            errs.append(f"{path}: {v} ≤ {s['exclusiveMinimum']}")
    if isinstance(v, list):
        if "minItems" in s and len(v) < s["minItems"]:
            errs.append(f"{path}: 원소 {s['minItems']}개 미만")
        if "maxItems" in s and len(v) > s["maxItems"]:
            errs.append(f"{path}: 원소 {s['maxItems']}개 초과")
        for i, x in enumerate(v):
            validate(x, s.get("items", {}), f"{path}[{i}]", errs)
    if isinstance(v, dict):
        props = s.get("properties")
        if props is not None:
            for k in s.get("required", []):
                if k not in v:
                    errs.append(f"{path}.{k}: 필수 필드 없음")
            for k, x in v.items():
                if k not in props:
                    errs.append(f"{path}.{k}: 스키마에 없는 필드")
                else:
                    validate(x, props[k], f"{path}.{k}", errs)
        elif isinstance(s.get("additionalProperties"), dict):
            for k, x in v.items():
                validate(x, s["additionalProperties"], f"{path}.{k}", errs)

def _errs(v, s):
    e = []
    validate(v, s, "", e)
    return e

def check_refs(o, where):
    if isinstance(o, dict):
        if "$ref" in o and o["$ref"].rsplit("/", 1)[1] not in S:
            fails.append(f"{where}: 없는 스키마 {o['$ref']}")
        for k, x in o.items():
            check_refs(x, f"{where}.{k}")
    elif isinstance(o, list):
        for x in o:
            check_refs(x, where)

# ───────────────────────── README 목록 표 → paths ─────────────────────────

def section(title):
    m = re.search(rf"^## {title}\n(.*?)(?=^## |\Z)", README, re.S | re.M)
    return m.group(1).strip()

rows = []
for line in re.findall(r"^\| \d+ \|.*$", README, re.M):
    c = [x.strip() for x in line.strip("|").split("|")]
    num, tag, method, path, auth, screen, summary, ex = c
    rows.append(dict(num=int(num), tag=tag, method=method, raw_path=path.strip("`"), auth=auth,
                     screen=screen, summary=summary, ex=ex))

def example_obj(files):
    return {f: {"summary": f"docs/api/{f}", "value": EXAMPLES[f]} for f in files}

def error_responses(codes):
    by_status = {}
    for c in codes:
        by_status.setdefault(ERR[c][0], []).append(c)
    out = {}
    for st in sorted(by_status):
        exs = {}
        for c in by_status[st]:
            val = {"code": c, "message": ERR[c][1]}
            if c == "INVALID_INPUT":
                val["fields"] = [{"field": "profile.gpa", "reason": "0.0~4.5, 소수 첫째 자리까지"}]
            exs[c] = {"value": val}
        out[str(st)] = {"description": " · ".join(by_status[st]),
                        "content": {"application/json": {"schema": R("Error"), "examples": exs}}}
    return out

paths, tags, used_examples = {}, [], set()
seen_ops = set()
for r in rows:
    path, _, query = r["raw_path"].partition("?")
    key = f"{r['method']} {path}"
    e = ENDPOINTS.get(key)
    if e is None:
        fails.append(f"README {r['num']}번 {key}: build_openapi.py ENDPOINTS에 없음 → 요청·응답 스키마를 정해 추가하세요")
        continue
    seen_ops.add(key)
    if r["tag"] not in tags:
        tags.append(r["tag"])

    # README 예시 칸 ↔ ENDPOINTS 예시·상태 코드가 같은지
    linked = set(re.findall(r"\]\(([\w.-]+\.json)\)", r["ex"]))
    mine = set((e.get("req") or (None, []))[1]) | {f for _, fs in e["ok"].values() for f in fs}
    if linked != mine:
        fails.append(f"{key}: README 예시 링크 {sorted(linked)} ≠ build_openapi.py {sorted(mine)}")
    for st in re.findall(r"\b(20[014])\b", r["ex"]):
        if int(st) not in e["ok"]:
            fails.append(f"{key}: README 예시 칸의 {st}이 ok 상태에 없음")

    op = {"tags": [r["tag"]], "operationId": e["op"], "summary": r["summary"],
          "x-auth": r["auth"],  # 공개·로그인·STUDENT·CENTER — 컨트롤러 @PublicApi·@RequireRole과 대조(ContractTest)
          "description": f"권한: **{r['auth']}** · 화면: {r['screen']} · 규칙은 [docs/api/README.md]({REPO_DOC}) '엔드포인트별 규칙'"}
    params = []
    for name in re.findall(r"\{(\w+)\}", path):
        params.append({"name": name, "in": "path", "required": True, "schema": ID})
    for name in re.findall(r"(\w+)=", query):
        params.append({"name": name, "in": "query", "required": False, "schema": DATE,
                       "description": "회차 모집기간 안의 날짜. 생략하면 rounds/current의 replay.defaultAsOf"})
    if params:
        op["parameters"] = params
    errors = list(e.get("errors", []))
    if e.get("req"):
        schema, files = e["req"]
        op["requestBody"] = {"required": True, "content": {"application/json": {
            "schema": R(schema), "examples": example_obj(files)}}}
        errors.insert(0, "INVALID_INPUT")
        for f in files:
            used_examples.add(f)
            for msg in _errs(EXAMPLES[f], R(schema)):
                fails.append(f"{f} ↔ {schema}{msg}")
    if r["auth"] != "공개":
        op["security"] = [{"bearerAuth": []}]
        errors += ["AUTH_REQUIRED", "TOKEN_EXPIRED"]
        if r["auth"] in ("STUDENT", "CENTER"):
            errors.append("FORBIDDEN_ROLE")
    responses = {}
    for st, (schema, files) in sorted(e["ok"].items()):
        desc = STATUS_TEXT[st] if e["op"] == "addPlanItem" else OK_TEXT[st]
        resp = {"description": desc}
        if schema:
            resp["content"] = {"application/json": {"schema": R(schema), "examples": example_obj(files)}}
            for f in files:
                used_examples.add(f)
                for msg in _errs(EXAMPLES[f], R(schema)):
                    fails.append(f"{f} ↔ {schema}{msg}")
        responses[str(st)] = resp
    for c in errors:
        if c not in ERR:
            fails.append(f"{key}: README 오류 코드 표에 없는 {c}")
    responses.update(error_responses([c for c in dict.fromkeys(errors) if c in ERR]))
    op["responses"] = responses
    paths.setdefault(path, {})[r["method"].lower()] = op

for key in ENDPOINTS:
    if key not in seen_ops:
        fails.append(f"{key}: build_openapi.py에는 있는데 README 목록 표에 없음")
unused = set(EXAMPLES) - used_examples
if unused:
    fails.append(f"스펙에 들어가지 않은 예시 파일: {sorted(unused)}")
if len(paths) == 0:
    fails.append("README 목록 표를 읽지 못함")

version = re.search(r"^version = '([^']+)'", GRADLE, re.M).group(1)
info_desc = "\n\n".join([
    "**계약 스펙** — 아직 구현되지 않은 엔드포인트도 보인다. 지금 코드에 있는 것만 보려면 위 드롭다운에서 '구현'을 고른다.",
    f"`scripts/build_openapi.py`가 `docs/api`(README 목록 표·예시 JSON)로 만든다. 손으로 고치지 않는다. 원본은 [docs/api/README.md]({REPO_DOC}).",
    "인증이 필요한 API는 오른쪽 **Authorize**에 `POST /api/auth/guest` 응답의 accessToken을 넣는다.",
    "## 공통 규칙 (README에서 옮김)",
    section("공통"),
])

spec = {
    "openapi": "3.1.0",
    "info": {"title": "현장뛰자 API — 계약", "version": version, "description": info_desc},
    "externalDocs": {"description": "docs/api/README.md", "url": REPO_DOC},
    "tags": [{"name": t} for t in tags],
    "paths": paths,
    "components": {
        "securitySchemes": {"bearerAuth": {"type": "http", "scheme": "bearer", "bearerFormat": "JWT"}},
        "schemas": dict(sorted(S.items())),
    },
}
check_refs(spec, "spec")

text = json.dumps(spec, ensure_ascii=False, indent=2) + "\n"
n_ops = sum(len(v) for v in paths.values())
summary = f"엔드포인트 {n_ops}개 · 스키마 {len(S)}개 · 예시 {len(used_examples)}개"

if "--check" in sys.argv:
    if not OUT.exists() or OUT.read_text(encoding="utf-8") != text:
        fails.append(f"{OUT.relative_to(ROOT)}이 docs/api와 다릅니다 → python scripts/build_openapi.py 로 다시 만들어 같이 커밋하세요")
else:
    if not fails:
        OUT.parent.mkdir(parents=True, exist_ok=True)
        OUT.write_text(text, encoding="utf-8", newline="\n")

if fails:
    print(f"OpenAPI 계약 스펙: 실패 {len(fails)}개 ({summary})")
    for f in fails:
        print(" -", f)
    sys.exit(1)
print(f"✓ OpenAPI 계약 스펙 {'최신' if '--check' in sys.argv else '생성'} — {summary}")
