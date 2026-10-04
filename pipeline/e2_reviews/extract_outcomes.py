"""E2 후속: 수기의 '실습 결과' 문단 → 사실 구절만(원문 그대로) → out/outcomes.json

    python extract_outcomes.py [--reviews out] [--model claude-sonnet-5-5]

- 입력은 run_extract.py 결과(out/*.json)의 records[].results 글이다. PDF를 다시 읽지 않는다.
- 수기는 '우수' 수기만 모은 것이라 긍정 쪽으로 치우쳐 있다. 그래서 감상·배운 점·평가·개인 진로(입사·채용 등)는
  버리고, 만든 결과물·맡은 일·참여한 프로젝트·채택된 제안처럼 확인할 수 있는 사실만 남긴다(ADR-0020).
- LLM이 고른 구절은 코드로 다시 거른다: 원문에 끊김 없이 있는지, 60자 이내인지, 감상·진로 낱말이 없는지.
  걸린 구절은 dropped에 이유와 함께 남긴다(사람 검토용).
- 결과는 build_seed.py --outcomes 로 시드의 testimonial.outcomes가 된다. out/은 커밋하지 않는다.
"""
import argparse, glob, hashlib, json, pathlib, re, sys
from concurrent.futures import ThreadPoolExecutor

HERE = pathlib.Path(__file__).parent
PRICE_IN, PRICE_OUT = 2.0, 10.0   # Sonnet 5.5, 백만 토큰당 USD (run_extract.py와 같음)
MAX_LEN = 60
MAX_ITEMS = 3
# 감상·자기 평가·개인 진로 낱말. 하나라도 있으면 사실 구절로 보지 않는다
BANNED = ["뿌듯", "보람", "뜻깊", "기뻤", "기쁜", "즐거", "느꼈", "느낄", "깨달", "배울 수", "배웠", "성장",
          "좋은 기회", "기회였", "입사", "채용", "정직원", "정규직", "합격", "취업연계", "노력하",
          "저의", "제가", "저는"]
PAIRS = {'""', "''", "“”", "‘’"}
# 문장 중간에서 끊긴 꼴(무엇을 했는지로 끝나지 않음)
CUT_ENDINGS = ("때", "날", "기도", "하고", "었고", "였고", "는데", "으며", "하며")
SCHEMA = {
    "type": "object",
    "properties": {"outcomes": {"type": "array", "items": {"type": "string"},
                                "description": "실습 결과 원문에서 그대로 잘라 낸 사실 구절 0~3개"}},
    "required": ["outcomes"],
    "additionalProperties": False,
}


def source_name(name):
    """분할·압축본 이름을 원본 이름으로(build_seed.py와 같은 규칙)."""
    return re.sub(r"_(part\d+|small)(?=\.pdf$)", "", name)


def squash(s):
    return re.sub(r"\s+", "", s or "")


def check(outcomes, results):
    """LLM 구절을 거른다. (남길 것, [(구절, 이유)])."""
    kept, dropped, seen = [], [], set()
    body = squash(results)
    for raw in outcomes:
        text = re.sub(r"\s+", " ", raw or "").strip()
        if len(text) >= 2 and text[0] + text[-1] in PAIRS:   # 구절 전체를 감싼 따옴표만 뗀다(안쪽 따옴표는 원문)
            text = text[1:-1].strip()
        reason = None
        if not text:
            reason = "빈 구절"
        elif squash(text) not in body:
            reason = "원문에 그대로 없음"
        elif len(text) > MAX_LEN:
            reason = f"{MAX_LEN}자 초과"
        elif any(w in text for w in BANNED):
            reason = "감상·평가·진로·1인칭 낱말"
        elif text.endswith(CUT_ENDINGS) or len(text.split()[-1]) < 2 or not text[-1].isalnum():
            reason = "문장 중간에서 끊김"   # 'ㅇㅇ을 맡'처럼 마지막 낱말이 한 글자거나 문장부호로 끝남
        elif squash(text) in seen:
            reason = "중복"
        elif len(kept) >= MAX_ITEMS:
            reason = f"{MAX_ITEMS}개 초과"
        if reason:
            dropped.append({"text": text, "reason": reason})
        else:
            kept.append(text)
            seen.add(squash(text))
    return kept, dropped


def ask(client, model, system, results, tries=2):
    """구절 목록과 비용. 응답이 비거나 JSON이 아니면 한 번 더 묻고, 그래도 안 되면 None(그 수기는 결과 없음)."""
    cost = 0.0
    for _ in range(tries):
        msg = client.messages.create(
            model=model,
            max_tokens=1000,
            system=system,
            messages=[{"role": "user", "content": f"[실습 결과]\n{results.strip()}"}],
            output_config={"format": {"type": "json_schema", "schema": SCHEMA}},
        )
        u = msg.usage
        cost += (u.input_tokens * PRICE_IN + u.output_tokens * PRICE_OUT) / 1e6
        text = "".join(b.text for b in msg.content if b.type == "text")
        try:
            return json.loads(text).get("outcomes", []), cost
        except json.JSONDecodeError:
            continue
    return None, cost


def recheck(a):
    """거르기 규칙을 고친 뒤 같은 LLM 결과에 다시 적용한다(LLM 출력은 돌릴 때마다 조금씩 달라서)."""
    out = pathlib.Path(a.out)
    data = json.loads(out.read_text(encoding="utf-8"))
    texts = {}
    for f in glob.glob(str(pathlib.Path(a.reviews) / "*.json")):
        if "수기" not in pathlib.Path(f).name:
            continue
        rec = json.loads(pathlib.Path(f).read_text(encoding="utf-8"))
        for t in rec["result"]["records"]:
            texts[(source_name(rec["source_file"]), t["text_page"])] = t.get("results") or ""
    for r in data["records"]:
        kept, dropped = check(r["outcomes"], texts[(r["source"], r["text_page"])])
        r["outcomes"], r["dropped"] = kept, r["dropped"] + dropped
        for d in dropped:
            print(f"{r['semester']} {r['institution']} p{r['text_page']}: 버림({d['reason']}) {d['text']}")
    out.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"다시 거름 → {out}")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--reviews", default=str(HERE / "out"), help="run_extract.py 결과 폴더")
    ap.add_argument("--model", default="claude-sonnet-5-5")
    ap.add_argument("--out", default=str(HERE / "out" / "outcomes.json"))
    ap.add_argument("--workers", type=int, default=6, help="동시에 보내는 요청 수")
    ap.add_argument("--recheck", action="store_true",
                    help="LLM을 다시 부르지 않고 --out의 남은 구절에 지금 거르기 규칙만 다시 적용한다")
    a = ap.parse_args()
    if a.recheck:
        return recheck(a)

    import anthropic  # 단위 테스트(check)는 키·패키지 없이 돌게 여기서 부른다

    system = (HERE / "prompt_outcomes.md").read_text(encoding="utf-8")
    client = anthropic.Anthropic(timeout=120)
    files = sorted(f for f in glob.glob(str(pathlib.Path(a.reviews) / "*.json")) if "수기" in pathlib.Path(f).name)
    if not files:
        sys.exit(f"{a.reviews}에 수기 추출 결과(*수기*.json)가 없습니다 — run_extract.py를 먼저 돌리세요")
    items = []
    for f in files:
        rec = json.loads(pathlib.Path(f).read_text(encoding="utf-8"))
        items += [(source_name(rec["source_file"]), t) for t in rec["result"]["records"]]

    def one(item):
        src, t = item
        results = t.get("results") or ""
        outcomes, cost = ask(client, a.model, system, results) if results.strip() else ([], 0.0)
        kept, dropped = check(outcomes, results) if outcomes is not None else ([], [{"text": "", "reason": "응답 해석 실패"}])
        return cost, {"source": src, "text_page": t["text_page"], "semester": t["semester"],
                      "institution": t["institution"], "department": t["department"],
                      "outcomes": kept, "dropped": dropped}

    with ThreadPoolExecutor(max_workers=a.workers) as pool:
        done = list(pool.map(one, items))   # 입력 순서를 지킨다
    total = sum(c for c, _ in done)
    records = [r for _, r in done]
    for r in records:
        print(f"{r['semester']} {r['institution']} / {r['department']} p{r['text_page']}: "
              f"사실 {len(r['outcomes'])} · 버림 {len(r['dropped'])}")
    out = pathlib.Path(a.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps({"model": a.model, "prompt_sha256": hashlib.sha256(system.encode()).hexdigest()[:12],
                               "cost_usd_est": round(total, 4), "records": records},
                              ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"\n수기 {len(records)}건, 약 ${total:.3f} → {out}\n다음: build_seed.py --outcomes {out}")


if __name__ == "__main__":
    main()
