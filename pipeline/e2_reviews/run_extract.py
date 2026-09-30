"""E2: 우수 참여수기 PDF → Claude(structured outputs) → out/*.json

사용법
  pip install anthropic pypdf openpyxl
  export ANTHROPIC_API_KEY=sk-ant-...        (Windows PowerShell: $env:ANTHROPIC_API_KEY="sk-ant-...")
  python run_extract.py "<수기 PDF 또는 폴더>" ... [--model claude-sonnet-5-5]

- 폴더를 주면 그 아래의 '*수기*.pdf'를 전부 찾습니다. '_small.pdf'(압축본)가 있으면 원본 대신 씁니다.
- 결과: out/<파일명>.json  (요청·응답 메타데이터 + 추출 결과)
"""
import argparse, base64, json, pathlib, sys, time

import anthropic

HERE = pathlib.Path(__file__).parent
PRICE_IN, PRICE_OUT = 2.0, 10.0          # Sonnet 5.5, 백만 토큰당 USD (기획안 11장 기준)
MAX_REQUEST_BYTES = 32 * 1024 * 1024     # Claude 요청 한도 32MB (base64는 원본의 약 4/3)


def find_pdfs(args):
    files = []
    for a in args:
        p = pathlib.Path(a)
        if p.is_dir():
            found = sorted(p.rglob("*수기*.pdf"))
            small = {f.name.replace("_small.pdf", ".pdf") for f in found if f.name.endswith("_small.pdf")}
            files += [f for f in found if f.name.endswith("_small.pdf") or f.name not in small]
        elif p.suffix.lower() == ".pdf":
            files.append(p)
        else:
            print(f"[건너뜀] PDF가 아님: {a}")
    return files


def extract(client, pdf, schema, system, model, max_tokens):
    data = pdf.read_bytes()
    b64 = base64.standard_b64encode(data).decode()
    if len(b64) > MAX_REQUEST_BYTES - 1_000_000:
        raise ValueError(f"base64 {len(b64)/1e6:.1f}MB — 32MB 요청 한도에 걸립니다. python compress_pdf.py \"{pdf}\" 로 압축한 뒤 다시 실행하세요")
    t0 = time.time()
    msg = client.messages.create(
        model=model,
        max_tokens=max_tokens,
        system=system,
        messages=[{
            "role": "user",
            "content": [
                {"type": "document", "source": {"type": "base64", "media_type": "application/pdf", "data": b64}},
                {"type": "text", "text": f"파일명: {pdf.name}\n이 참여수기 모음에서 실습생별 내용을 스키마에 맞게 추출하세요."},
            ],
        }],
        output_config={"format": {"type": "json_schema", "schema": schema}},
    )
    elapsed = time.time() - t0
    text = "".join(b.text for b in msg.content if b.type == "text")
    try:
        result = json.loads(text)
    except json.JSONDecodeError:
        result = None
    u = msg.usage
    cost = (u.input_tokens * PRICE_IN + u.output_tokens * PRICE_OUT) / 1e6
    return {
        "source_file": pdf.name,
        "source_bytes": len(data),
        "model": model,
        "stop_reason": msg.stop_reason,
        "elapsed_sec": round(elapsed, 1),
        "usage": {"input_tokens": u.input_tokens, "output_tokens": u.output_tokens},
        "cost_usd_est": round(cost, 4),
        "raw_text_if_unparsed": None if result is not None else text,
        "result": result,
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("paths", nargs="+")
    ap.add_argument("--model", default="claude-sonnet-5-5")
    ap.add_argument("--max-tokens", type=int, default=20000)
    ap.add_argument("--out", default=str(HERE / "out"))
    a = ap.parse_args()

    schema = json.loads((HERE / "schema.json").read_text(encoding="utf-8"))
    system = (HERE / "prompt_system.md").read_text(encoding="utf-8")
    out = pathlib.Path(a.out); out.mkdir(parents=True, exist_ok=True)
    client = anthropic.Anthropic(timeout=600)

    pdfs = find_pdfs(a.paths)
    if not pdfs:
        sys.exit("수기 PDF를 찾지 못했습니다.")
    total = 0.0
    for pdf in pdfs:
        print(f"→ {pdf.name} ({pdf.stat().st_size/1e6:.1f}MB) ...", flush=True)
        try:
            rec = extract(client, pdf, schema, system, a.model, a.max_tokens)
        except anthropic.BadRequestError as e:
            print(f"   [실패] 400: {e.message}")
            continue
        except Exception as e:  # noqa: BLE001
            print(f"   [실패] {type(e).__name__}: {e}")
            continue
        (out / f"{pdf.stem}.json").write_text(json.dumps(rec, ensure_ascii=False, indent=2), encoding="utf-8")
        total += rec["cost_usd_est"]
        jobs = len(rec["result"]["records"]) if rec["result"] else "파싱 실패"
        warn = "  ⚠ max_tokens에서 잘림 — PDF를 반으로 나눠 다시 실행하세요" if rec["stop_reason"] == "max_tokens" else ""
        print(f"   완료 {rec['elapsed_sec']}s, 입력 {rec['usage']['input_tokens']:,} / 출력 {rec['usage']['output_tokens']:,} 토큰,"
              f" 약 ${rec['cost_usd_est']}, 수기 {jobs}건{warn}")
    print(f"\n합계 약 ${total:.3f}. 다음: python score.py")


if __name__ == "__main__":
    main()
