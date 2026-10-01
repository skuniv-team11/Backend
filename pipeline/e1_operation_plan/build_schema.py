"""추출 스키마 생성기. 스키마를 고칠 때는 이 파일을 고치고 다시 실행한다.

만드는 파일
- schema_part_institution.json — 1/2 호출: 기관 현황 + 문서 내부 불일치(전체 필드)
- schema_part_jobs.json        — 2/2 호출: 직무(전체 필드)
- schema_lite.json             — E1 채점용 핵심 필드(호출 1번)
전체 필드를 한 스키마에 담으면 문법 한도를 넘어서(아래) PDF 1건을 호출 2번으로 나눈다(ADR-0003, Java E3와 같은 구조).

Claude structured outputs 제약(2026-09 문서 기준):
- 모든 object는 additionalProperties: false
- 선택(optional) 속성은 전체 24개, 유니언 타입(anyOf, ["string","null"])은 16개까지
  -> 이 스키마는 모든 속성을 required로 두고 null을 쓰지 않는다. 값이 없으면 "" / page 0.
- minLength, pattern, minimum 같은 제약은 지원하지 않음 -> 숫자·형식 검증은 score.py(코드)가 한다.
- 컴파일된 문법에 크기 한도가 있다(초과 시 400 "The compiled grammar is too large").
  필드 수가 아니라 스키마 전체 크기가 기준이라, 잎 객체를 $defs 로 모아 참조한다(refify).
  2026-09-30 확인: lite 는 $ref 로 통과, 전체 스키마는 $ref 를 써도 여전히 한도를 넘는다.
"""
import json, pathlib

def obj(props):
    return {"type": "object", "properties": props, "required": list(props), "additionalProperties": False}


def text_field(desc):
    # 여기서는 펼쳐 두고, 마지막에 refify 가 같은 모양끼리 $defs 로 모은다
    f = obj({
        "value": {"type": "string", "description": "문서에 적힌 값 그대로. 없으면 빈 문자열"},
        "page": {"type": "integer", "description": "근거가 있는 PDF 쪽 번호(1부터). 없으면 0"},
        "quote": {"type": "string", "description": "근거 원문 그대로(최대 80자). 없으면 빈 문자열"},
    })
    f["description"] = desc
    return f

def choice_field(desc, options, extras=True):
    opts = options + (["미기재", "판독불가"] if extras else [])
    return {
        "type": "object",
        "description": desc,
        "properties": {
            "value": {"type": "string", "enum": opts},
            "page": {"type": "integer", "description": "근거가 있는 PDF 쪽 번호(1부터). 없으면 0"},
            "quote": {"type": "string", "description": "근거 원문 그대로(최대 80자). 없으면 빈 문자열"},
        },
        "required": ["value", "page", "quote"],
        "additionalProperties": False,
    }

def multi_field(desc, options):
    return {
        "type": "object",
        "description": desc,
        "properties": {
            "value": {"type": "array", "items": {"type": "string", "enum": options}},
            "page": {"type": "integer", "description": "근거가 있는 PDF 쪽 번호(1부터). 없으면 0"},
            "quote": {"type": "string", "description": "근거 원문 그대로(최대 80자). 없으면 빈 문자열"},
        },
        "required": ["value", "page", "quote"],
        "additionalProperties": False,
    }

institution = obj({
    "name": text_field("기관(법인)명"),
    "business_no": text_field("사업자등록번호"),
    "opened_on": text_field("개업년월일(적힌 그대로)"),
    "ksic_code": text_field("한국표준산업분류코드. 쪽마다 다르면 첫 쪽 값을 넣고 inconsistencies에 기록"),
    "employees": text_field("종업원 수(적힌 그대로)"),
    "revenue": text_field("매출액(적힌 그대로)"),
    "address": text_field("사업장 소재지"),
    "homepage": text_field("홈페이지"),
    "size": choice_field("기관현황 규모 체크", ["대기업", "중견기업", "중소기업", "공공기관", "협회/기타"]),
    "listing": choice_field("상장여부 체크", ["코스피", "코스닥", "비상장"]),
    "business_type": text_field("사업의 종류(업태)"),
    "business_item": text_field("사업의 종류(종목)"),
    "daily_hours": text_field("정규 근로시간 1일 기준 시간"),
    "weekly_hours": text_field("정규 근로시간 1주 기준 시간"),
    "weekly_days": text_field("정규 근로일수(주 N일)"),
    "work_days_text": text_field("근로요일(예: 월~금)"),
    "selection_method": text_field("전형방법"),
    "application_deadline": text_field("접수마감일자(일정별도협의 표시 포함, 적힌 그대로)"),
    "interview_date": text_field("면접일자(적힌 그대로)"),
    "final_selection_date": text_field("최종선발일자(적힌 그대로)"),
})

weekly_item = obj({
    "weeks": {"type": "string", "description": "주차 표기(예: 1~2주차)"},
    "content": {"type": "string", "description": "그 주차의 계획 내용 원문"},
})

job = obj({
    "department": text_field("부서명"),
    "job_title": text_field("직무명"),
    "work_address": text_field("직무기술서의 주소(근무지)"),
    "course": choice_field("운영과정 체크", ["방학과정", "학기과정", "방학/학기 연계과정"]),
    "job_type": choice_field("운영유형 체크", ["직무체험형", "채용연계형"]),
    "period": text_field("실습기간(연도 오기가 있어도 적힌 그대로)"),
    "hours": text_field("정규실습 시간(적힌 그대로)"),
    "weekdays": multi_field("실습요일 중 체크된 요일", ["월", "화", "수", "목", "금", "토", "일"]),
    "overtime": choice_field("연장실습 여부 체크", ["없음", "상황별 실시", "주기적/상시적 실시"]),
    "labor_contract": choice_field("별도 근로계약 체결 여부 체크", ["Y", "N"]),
    "stipend_basis": choice_field("정규실습시간 실습지원비 지급기준", ["월 기준", "시간 기준"]),
    "stipend_amount": text_field("정규실습시간 실습지원비 금액(적힌 그대로, 예: 1,620,000)"),
    "overtime_pay": text_field("연장실습시간 지원비(적힌 그대로)"),
    "pay_day": text_field("지급예정일(당월/익월 N일, 적힌 그대로)"),
    "benefits": multi_field("기타 지원 사항 중 체크된 항목", ["식사", "교통", "기숙사", "현물"]),
    "education_goal": text_field("교육목표 원문"),
    "job_overview": text_field("직무개요 원문"),
    "weekly_plan": {"type": "array", "items": weekly_item, "description": "운영/지도 계획의 주차별 항목. 없으면 빈 배열"},
    "major_requirement": text_field("학생요건의 전공(인원) 칸 원문 전체"),
    "headcount": text_field("모집 인원(전공(인원) 칸 등에 적힌 그대로, 예: 1명)"),
    "grade_requirement": text_field("학년 요건 원문"),
    "gpa_requirement": text_field("학점/평점 요건 원문"),
    "competencies": text_field("요구역량 원문"),
    "notes": text_field("기타사항 원문"),
    "portfolio": choice_field("포트폴리오: '필수/제출'이면 필수, '우대'면 우대, 언급 없으면 언급 없음", ["필수", "우대", "언급 없음"], extras=False),
    "certificate": choice_field("자격증: '필수'면 필수, '우대'면 우대, 언급 없으면 언급 없음", ["필수", "우대", "언급 없음"], extras=False),
})

inconsistency = obj({
    "description": {"type": "string", "description": "무엇과 무엇이 어떻게 다른지 한 문장"},
    "page_a": {"type": "integer"},
    "quote_a": {"type": "string"},
    "page_b": {"type": "integer"},
    "quote_b": {"type": "string"},
})

schema = {
    "type": "object",
    "properties": {
        "institution": institution,
        "jobs": {"type": "array", "items": job, "description": "직무 1개 = 팀 1개. 직무기술서 블록의 부서명 칸에 팀이 여러 개 적혀 있으면 팀마다 1개(시스템 프롬프트 '직무(jobs) 나누기' 참고)"},
        "inconsistencies": {"type": "array", "items": inconsistency,
                            "description": "같은 문서 안에서 같은 항목이 서로 다르게 적힌 경우만. 없으면 빈 배열"},
    },
    "required": ["institution", "jobs", "inconsistencies"],
    "additionalProperties": False,
}

def pick(o, keys):
    return obj({k: o["properties"][k] for k in keys})

# 대비책: "Schema is too complex for compilation" 400 오류가 나면 핵심 필드만 담은 lite 스키마로 돌린다
schema_lite = {
    "type": "object",
    "properties": {
        "institution": pick(institution, ["name", "business_no", "opened_on", "ksic_code", "employees",
                                          "size", "listing", "selection_method"]),
        "jobs": {"type": "array", "description": schema["properties"]["jobs"]["description"],
                 "items": pick(job, ["department", "job_title", "job_type", "period", "hours", "weekdays",
                                     "overtime", "labor_contract", "stipend_amount", "benefits",
                                     "major_requirement", "headcount", "grade_requirement", "gpa_requirement",
                                     "portfolio"])},
        "inconsistencies": schema["properties"]["inconsistencies"],
    },
    "required": ["institution", "jobs", "inconsistencies"],
    "additionalProperties": False,
}

def refify(sc):
    """value/page/quote 잎 객체를 $defs 로 모으고 $ref 로 바꾼다.

    잎이 스키마마다 100개 가까이 같은 모양으로 반복돼 컴파일된 문법이 한도를 넘는다.
    $ref 옆에 description 을 나란히 두는 형태는 API 가 받아들인다(2026-09-30 확인).
    """
    defs, index = {}, {}

    def walk(node):
        if isinstance(node, dict):
            props = node.get("properties")
            if props and set(props) == {"value", "page", "quote"}:
                key = json.dumps(props["value"], ensure_ascii=False, sort_keys=True)
                if key not in index:
                    index[key] = f"Sourced{len(index)}"
                    defs[index[key]] = {k: v for k, v in node.items() if k != "description"}
                ref = {"$ref": f"#/$defs/{index[key]}"}
                if "description" in node:
                    ref["description"] = node["description"]
                return ref
            return {k: walk(v) for k, v in node.items()}
        if isinstance(node, list):
            return [walk(v) for v in node]
        return node

    out = walk(sc)
    out["$defs"] = defs
    return out


# 본 추출(호출 2번). Java의 OperationPlanInstitutionPart / OperationPlanJobsPart 와 같은 나눔
schema_part_institution = {
    "type": "object",
    "properties": {"institution": institution, "inconsistencies": schema["properties"]["inconsistencies"]},
    "required": ["institution", "inconsistencies"],
    "additionalProperties": False,
}
schema_part_jobs = {
    "type": "object",
    "properties": {"jobs": schema["properties"]["jobs"]},
    "required": ["jobs"],
    "additionalProperties": False,
}

here = pathlib.Path(__file__).parent
for name, sc in [("schema_part_institution.json", schema_part_institution),
                 ("schema_part_jobs.json", schema_part_jobs),
                 ("schema_lite.json", schema_lite)]:
    sc = refify(sc)
    (here / name).write_text(json.dumps(sc, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"wrote {here / name}  ($defs {len(sc['$defs'])}개)")
