"""E8 ② 후보마다 사진인지·어떤 장면인지·인쇄된 캡션을 Claude(이미지 + structured outputs)로 읽는다 (ADR-0030)

    ANTHROPIC_API_KEY=... python classify_photos.py [--out out] [--model claude-sonnet-5-5]

- 입력: extract_photos.py의 out/candidates.json과 번호 상자를 그린 쪽 그림(out/pages/*.jpg). 쪽 하나에 한 번 부른다.
- 결과: out/classified.json — 쪽마다 응답 원문과 번호별 {kind, scene, caption}. 이미 있는 쪽은 다시 부르지 않는다.
- 표준 라이브러리만 쓴다. 키는 환경변수로만 받고 출력하지 않는다.
"""
import argparse, base64, concurrent.futures as cf, json, os, pathlib, time, urllib.error, urllib.request

HERE = pathlib.Path(__file__).resolve().parent
SCHEMA = json.loads((HERE / "schema.json").read_text(encoding="utf-8"))
SYSTEM = (HERE / "prompt_system.md").read_text(encoding="utf-8")


def call(model, page_jpg, count, key):
    b64 = base64.standard_b64encode(page_jpg.read_bytes()).decode()
    body = {"model": model, "max_tokens": 2000, "system": SYSTEM,
            "thinking": {"type": "disabled" if "haiku" in model else "between_tools"},
            "output_config": {"format": {"type": "json_schema", "schema": SCHEMA}},
            "messages": [{"role": "user", "content": [
                {"type": "image", "source": {"type": "base64", "media_type": "image/jpeg", "data": b64}},
                {"type": "text", "text": f"번호 상자는 1~{count}번입니다. 번호마다 답하세요."}]}]}
    data = json.dumps(body).encode()
    for attempt in range(6):
        req = urllib.request.Request("https://api.anthropic.com/v1/messages", data=data, headers={
            "x-api-key": key, "anthropic-version": "2023-06-01", "content-type": "application/json"})
        try:
            r = json.load(urllib.request.urlopen(req, timeout=180))
            text = "".join(b.get("text", "") for b in r["content"] if b.get("type") == "text")
            return json.loads(text), r.get("usage", {})
        except urllib.error.HTTPError as e:
            if e.code in (429, 500, 502, 503, 529):
                time.sleep(4 * (attempt + 1))
                continue
            raise RuntimeError(f"{e.code} {e.read().decode()[:300]}")
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError):
            time.sleep(4 * (attempt + 1))
    raise RuntimeError("다시 시도해도 실패")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", default=str(HERE / "out"))
    ap.add_argument("--model", default="claude-sonnet-5-5")
    a = ap.parse_args()
    key = os.environ.get("ANTHROPIC_API_KEY") or raise_missing()
    out = pathlib.Path(a.out)
    cands = json.loads((out / "candidates.json").read_text(encoding="utf-8"))["candidates"]
    pages = {}
    for c in cands:
        if c.get("manual"):
            continue          # 사람이 자리·캡션을 적은 것은 부르지 않는다
        pages.setdefault((c["doc"], c["page"]), []).append(c)
    path = out / "classified.json"
    done = json.loads(path.read_text(encoding="utf-8")) if path.exists() else {}
    todo = [(d, p) for (d, p) in pages if f"{d:02d}-{p:02d}" not in done]

    def work(dp):
        d, p = dp
        result, usage = call(a.model, out / "pages" / f"{d:02d}-{p:02d}.jpg", len(pages[dp]), key)
        return f"{d:02d}-{p:02d}", {"photo_section": result["photo_section"], "items": result["items"], "usage": usage}

    with cf.ThreadPoolExecutor(4) as ex:
        for k, v in ex.map(work, todo):
            done[k] = v
            path.write_text(json.dumps(done, ensure_ascii=False, indent=1), encoding="utf-8")
    tin = sum(v["usage"].get("input_tokens", 0) for v in done.values())
    tout = sum(v["usage"].get("output_tokens", 0) for v in done.values())
    print(f"쪽 {len(done)}개(이번에 {len(todo)}개) · 입력 {tin:,} · 출력 {tout:,} 토큰 → {path}")


def raise_missing():
    raise SystemExit("환경변수 ANTHROPIC_API_KEY를 넣으세요")


if __name__ == "__main__":
    main()
