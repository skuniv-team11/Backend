"""E2 참여수기 추출 스키마 생성기. E1과 같은 원칙: 모든 필드 required, null 없음, 값이 없으면 ""/0."""
import json, pathlib

def obj(props, desc=None):
    o = {"type": "object", "properties": props, "required": list(props), "additionalProperties": False}
    if desc: o["description"] = desc
    return o

S = lambda d: {"type": "string", "description": d}
I = lambda d: {"type": "integer", "description": d}

record = obj({
    "semester": S("학기 표기(예: 2025-2학기). 적힌 그대로"),
    "institution": S("기관명(적힌 그대로)"),
    "department": S("부서(적힌 그대로)"),
    "one_line": S("'나에게 현장실습은 ___ 이다'의 빈칸에 적힌 말"),
    "company_intro": S("[1] 기업 및 직무 소개 원문"),
    "activities": {"type": "array", "items": {"type": "string"}, "description": "[2] 실습 내용의 항목들(원문 그대로)"},
    "results": S("[3] 실습결과 원문"),
    "reflection": S("[4] 참여소감 원문"),
    "major": S("학과(적힌 그대로)"),
    "grade": S("학년(적힌 그대로)"),
    "text_page": I("글이 있는 쪽 번호(PDF 순서, 1부터)"),
    "photo_page": I("같은 사람의 사진 쪽 번호. 없으면 0"),
}, "실습생 1명의 수기")

schema = obj({"records": {"type": "array", "items": record, "description": "실습생 1명당 1개"}})
out = pathlib.Path(__file__).with_name("schema.json")
out.write_text(json.dumps(schema, ensure_ascii=False, indent=2), encoding="utf-8")
print("wrote", out)
