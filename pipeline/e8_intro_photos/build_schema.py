"""E8 사진 분류 응답 스키마 → schema.json (structured outputs 제약, ADR-0003). 스키마는 이 파일을 고쳐 다시 만든다."""
import json, pathlib

KINDS = ["사진", "로고", "그래픽", "문서", "직인서명"]
SCENES = ["근무환경", "활동행사", "건물전경", "제품", "인물", "기타"]


def obj(props):
    return {"type": "object", "properties": props, "required": list(props), "additionalProperties": False}


schema = obj({
    "photo_section": {"type": "boolean",
                      "description": "이 쪽의 사진이 소개서 서식의 '회사 전경 및 활동사진' 칸(표 안 사진 + 캡션 칸)에 들어 있으면 true"},
    "items": {"type": "array", "items": obj({
    "n": {"type": "integer", "description": "빨간 상자 번호"},
    "kind": {"type": "string", "enum": KINDS},
    "scene": {"type": "string", "enum": SCENES, "description": "사진이 아니면 기타"},
    "caption": {"type": "string", "description": "상자 바로 아래나 위에 인쇄된 설명 원문. 없으면 빈 문자열"},
})}})

if __name__ == "__main__":
    out = pathlib.Path(__file__).with_name("schema.json")
    out.write_text(json.dumps(schema, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    print(f"→ {out}")
