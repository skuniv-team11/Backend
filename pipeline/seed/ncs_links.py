"""능력단위끼리 연결 초안: 직무 세분류의 능력단위 → 넓혀 갈 세분류의 능력단위 (ADR-0032)

    python ncs_links.py --draft          # 연결이 하나도 없는 넓힘만 AI(Claude)가 초안을 채운다 → curated/ncs_unit_links.csv
    python ncs_seed.py ...               # CSV를 검사해 ncs.json에 넣는다
    python to_sql.py

커리어 리포트에서 '채운 능력단위와 많이 이어지는 순서'로 넓혀 갈 직무를 보여 주고, 이어진 단위(넓혀 갈 단위 ← 채운 단위)와
더 채울 것을 보여 주려고 쓴다. 초안은 사람이 보고 맞으면 checked에 Y를 적는다(틀리면 줄을 지우거나 고친다).
응답에는 checked가 그대로 나가 화면이 'AI 초안 · 확인 전'을 표시한다.

- 입력: ncs.json(ncs_seed.py 결과 — 능력단위 이름·수준·정의, 넓힘 3개). 실습 내용 같은 학생 정보는 보내지 않는다
- 모델: 환경변수 NCS_LINKS_MODEL(기본 claude-sonnet-5-5). 키는 ANTHROPIC_API_KEY로만 받는다
- 출력 형식은 JSON 스키마로 고정하고, 코드가 목록에 없거나 규칙을 어긴 연결은 버린다
- 표준 라이브러리만 쓴다
"""
import argparse, csv, json, os, pathlib, sys, time, urllib.error, urllib.request

HERE = pathlib.Path(__file__).resolve().parent
LINKS = HERE / "curated" / "ncs_unit_links.csv"
FIELDS = ["from_code", "to_code", "from_unit", "to_unit", "note", "checked"]
LINKS_MAX = 5
NOTE_MAX = 60

SYSTEM = """너는 NCS(국가직무능력표준) 능력단위를 잘 아는 직무 분석가다.
학생이 '직무 세분류' 하나로 한 학기 현장실습을 했다. 그 세분류의 능력단위와, 실습 뒤 넓혀 갈 세분류 3개의 능력단위가 주어진다.
넓혀 갈 세분류마다, 직무 세분류의 어떤 능력단위를 해 본 학생이 넓혀 갈 세분류의 어떤 능력단위를 바로 이어서 할 수 있는지 연결을 고른다.

규칙
- 연결 하나 = 직무 세분류 단위 하나 → 넓혀 갈 세분류 단위 하나. 같은 지식·기술을 그대로 쓰거나, 앞 단계 일이 그 단위의 입력이 될 때만 잇는다.
- 넓혀 갈 단위 하나에는 직무 단위 하나만 잇는다(가장 강한 것). 직무 단위 하나는 여러 곳에 이어도 된다.
- 넓혀 갈 세분류마다 0~5개. 억지로 채우지 않는다. 이름만 비슷하고 하는 일이 다르면 잇지 않는다.
- note는 왜 이어지는지 40자 안팎의 짧은 구(예: "시장 조사 결과를 광고 대상 설정에 그대로 씀"). 존댓말·문장부호 끝맺음 없이.
- 코드는 목록에 적힌 그대로 쓴다."""

SCHEMA = {
    "type": "object", "additionalProperties": False, "required": ["paths"],
    "properties": {"paths": {"type": "array", "items": {
        "type": "object", "additionalProperties": False, "required": ["toCode", "links"],
        "properties": {
            "toCode": {"type": "string"},
            "links": {"type": "array", "items": {
                "type": "object", "additionalProperties": False, "required": ["fromUnit", "toUnit", "note"],
                "properties": {"fromUnit": {"type": "string"}, "toUnit": {"type": "string"}, "note": {"type": "string"}}}}}}}},
}


def read_links():
    if not LINKS.exists():
        return []
    with open(LINKS, encoding="utf-8") as f:
        return list(csv.DictReader(f))


def write_links(rows):
    rows = sorted(rows, key=lambda r: (r["from_code"], r["to_code"], r["to_unit"]))
    with open(LINKS, "w", encoding="utf-8", newline="\n") as f:
        w = csv.DictWriter(f, fieldnames=FIELDS, lineterminator="\n")
        w.writeheader()
        w.writerows(rows)


def unit_lines(units, defn=150):
    return "\n".join(f"- {u['code']} {u['name']} (수준 {u['level'] or '—'}): {(u['definition'] or '—')[:defn]}" for u in units)


def user_message(ncs, code):
    subs = {s["code"]: s for s in ncs["ncs_subcategory"]}
    units = {}
    for u in ncs["ncs_unit"]:
        units.setdefault(u["subcategory_code"], []).append(u)
    out = [f"# 직무 세분류 {code} {subs[code]['name']} ({subs[code]['middle_name']} > {subs[code]['small_name']})",
           unit_lines(units[code]), ""]
    for e in (e for e in ncs["ncs_expand"] if e["from_code"] == code):
        t = e["to_code"]
        out += [f"# 넓혀 갈 세분류 {e['rank']}: {t} {subs[t]['name']} ({subs[t]['middle_name']} > {subs[t]['small_name']})",
                unit_lines(units[t], 110), ""]
    return "\n".join(out)


def call(model, key, user):
    body = {"model": model, "max_tokens": 4000, "system": SYSTEM,
            "messages": [{"role": "user", "content": user}],
            "thinking": {"type": "between_tools"},
            "output_config": {"format": {"type": "json_schema", "schema": SCHEMA}}}
    data = json.dumps(body).encode()
    for attempt in range(5):
        req = urllib.request.Request("https://api.anthropic.com/v1/messages", data=data, headers={
            "x-api-key": key, "anthropic-version": "2023-06-01", "content-type": "application/json"})
        try:
            r = json.load(urllib.request.urlopen(req, timeout=180))
            text = "".join(b.get("text", "") for b in r["content"] if b.get("type") == "text")
            return json.loads(text), r.get("usage", {})
        except urllib.error.HTTPError as e:
            if e.code in (429, 500, 502, 503, 529):
                time.sleep(5 * (attempt + 1))
                continue
            sys.exit(f"API 오류 {e.code}: {e.read().decode()[:300]}")
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError):
            time.sleep(5 * (attempt + 1))
    sys.exit("API 재시도를 다 썼습니다")


def accept(ncs, code, draft):
    """초안 → 규칙을 지킨 연결만(넓힘마다 최대 5개, 넓혀 갈 단위 하나에 하나)."""
    by_sub = {}
    for u in ncs["ncs_unit"]:
        by_sub.setdefault(u["subcategory_code"], set()).add(u["code"])
    targets = {e["to_code"] for e in ncs["ncs_expand"] if e["from_code"] == code}
    rows, dropped = [], 0
    for p in draft.get("paths", []):
        t = p.get("toCode", "").strip()
        if t not in targets:
            dropped += len(p.get("links", []))
            continue
        seen = set()
        for l in p.get("links", []):
            fu, tu, note = l["fromUnit"].strip(), l["toUnit"].strip(), " ".join(l["note"].split())
            if fu not in by_sub[code] or tu not in by_sub[t] or tu in seen or len(seen) >= LINKS_MAX or not note:
                dropped += 1
                continue
            seen.add(tu)
            rows.append({"from_code": code, "to_code": t, "from_unit": fu, "to_unit": tu,
                         "note": note[:NOTE_MAX], "checked": ""})
    return rows, dropped


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--draft", action="store_true", help="연결이 없는 넓힘만 AI 초안을 채운다")
    ap.add_argument("--ncs", default=str(HERE / "ncs.json"))
    ap.add_argument("--only", help="이 직무 세분류만(쉼표로 여럿)")
    a = ap.parse_args()
    ncs = json.loads(pathlib.Path(a.ncs).read_text(encoding="utf-8"))
    rows = read_links()
    have = {(r["from_code"], r["to_code"]) for r in rows}
    codes = sorted({j["subcategory_code"] for j in ncs["job_ncs"]})
    if a.only:
        codes = [c for c in codes if c in a.only.split(",")]
    todo = [c for c in codes if any((c, e["to_code"]) not in have for e in ncs["ncs_expand"] if e["from_code"] == c)]
    print(f"직무 세분류 {len(codes)}개 중 초안이 필요한 것 {len(todo)}개 · 연결 {len(rows)}줄(확인 {sum(r['checked'] == 'Y' for r in rows)})")
    if not a.draft or not todo:
        return
    key = os.environ.get("ANTHROPIC_API_KEY", "")
    if not key:
        sys.exit("ANTHROPIC_API_KEY가 없습니다")
    model = os.environ.get("NCS_LINKS_MODEL", "claude-sonnet-5-5")
    total = {"input_tokens": 0, "output_tokens": 0}
    for code in todo:
        draft, usage = call(model, key, user_message(ncs, code))
        new, dropped = accept(ncs, code, draft)
        new = [r for r in new if (r["from_code"], r["to_code"]) not in have]  # 사람이 이미 손댄 넓힘은 그대로 둔다
        rows += new
        write_links(rows)
        for k in total:
            total[k] += usage.get(k, 0)
        print(f"  {code}: 연결 {len(new)}개, 버림 {dropped}개 (입력 {usage.get('input_tokens')} · 출력 {usage.get('output_tokens')})")
    print(f"→ {LINKS} · 토큰 입력 {total['input_tokens']} · 출력 {total['output_tokens']} · 모델 {model}")


if __name__ == "__main__":
    main()
