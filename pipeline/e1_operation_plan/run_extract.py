"""운영계획서 PDF → Claude(structured outputs) → out/*.json

사용법
  pip install anthropic pypdf openpyxl
  export ANTHROPIC_API_KEY=sk-ant-...        (Windows PowerShell: $env:ANTHROPIC_API_KEY="sk-ant-...")
  python run_extract.py "<운영계획서 PDF 또는 폴더>" ... [--lite] [--model claude-sonnet-5-5]

- 기본(본 추출): PDF 1건을 호출 2번으로 뽑는다 — ① 기관 + 문서 내부 불일치 ② 직무. 전체 필드(시드에 씀).
  한 스키마에 다 담으면 문법 한도를 넘는다(ADR-0003). 지시문은 Java OperationPlanRequests 와 같다.
- --lite: E1 채점용 핵심 필드만, 호출 1번.
- 폴더를 주면 그 아래의 '*운영계획서*.pdf'를 전부 찾습니다.
- 결과: out/<파일명>.json  (쪽수·호출별 메타데이터 + 합친 추출 결과 {institution, jobs, inconsistencies})
"""
import argparse, base64, io, json, pathlib, sys, time

import anthropic
from pypdf import PdfReader

HERE = pathlib.Path(__file__).parent
PRICE_IN, PRICE_OUT = 2.0, 10.0          # Sonnet 5.5, 백만 토큰당 USD (기획안 11장 기준)
MAX_REQUEST_BYTES = 32 * 1024 * 1024     # Claude 요청 한도 32MB (base64는 원본의 약 4/3)


def find_pdfs(args):
    files = []
    for a in args:
        p = pathlib.Path(a)
        if p.is_dir():
            files += sorted(p.rglob("*운영계획서*.pdf"))
        elif p.suffix.lower() == ".pdf":
            files.append(p)
        else:
            print(f"[건너뜀] PDF가 아님: {a}")
    return files


# 호출별 지시문. 시스템 프롬프트는 두 호출이 같고(PromptSyncTest), 어느 부분을 뽑는지는 여기서만 알린다
PART_INSTITUTION = ("이 운영계획서에서 기관 현황과 문서 내부 불일치를 스키마에 맞게 추출하세요."
                    " 직무([붙임1] 운영 계획 및 직무기술서)는 다른 호출에서 따로 뽑으니 여기서는 다루지 않습니다.")
PART_JOBS = ("이 운영계획서의 [붙임1] 운영 계획 및 직무기술서에서 직무를 스키마에 맞게 추출하세요."
             " 기관 현황과 문서 내부 불일치는 다른 호출에서 따로 뽑으니 여기서는 다루지 않습니다.")
LITE = "이 운영계획서를 스키마에 맞게 추출하세요."


def call(client, pdf_name, b64, schema, system, model, max_tokens, instruction):
    t0 = time.time()
    msg = client.messages.create(
        model=model,
        max_tokens=max_tokens,
        system=system,
        messages=[{
            "role": "user",
            "content": [
                {"type": "document", "source": {"type": "base64", "media_type": "application/pdf", "data": b64}},
                {"type": "text", "text": f"파일명: {pdf_name}\n{instruction}"},
            ],
        }],
        output_config={"format": {"type": "json_schema", "schema": schema}},
    )
    text = "".join(b.text for b in msg.content if b.type == "text")
    try:
        result = json.loads(text)
    except json.JSONDecodeError:
        result = None
    u = msg.usage
    return {
        "stop_reason": msg.stop_reason,
        "elapsed_sec": round(time.time() - t0, 1),
        "usage": {"input_tokens": u.input_tokens, "output_tokens": u.output_tokens},
        "cost_usd_est": round((u.input_tokens * PRICE_IN + u.output_tokens * PRICE_OUT) / 1e6, 4),
        "raw_text_if_unparsed": None if result is not None else text,
        "result": result,
    }


def extract(client, pdf, parts, system, model, max_tokens):
    """parts: [(스키마, 지시문)]. 호출 결과를 한 객체로 합친다(키가 겹치지 않는다)."""
    data = pdf.read_bytes()
    b64 = base64.standard_b64encode(data).decode()
    if len(b64) > MAX_REQUEST_BYTES - 1_000_000:
        raise ValueError(f"base64 {len(b64)/1e6:.1f}MB — 32MB 요청 한도에 걸립니다. 압축하거나 쪽을 나누세요")
    calls = [call(client, pdf.name, b64, schema, system, model, max_tokens, instruction) for schema, instruction in parts]
    merged = {}
    for c in calls:
        merged = None if merged is None or c["result"] is None else {**merged, **c["result"]}
    return {
        "source_file": pdf.name,
        "source_bytes": len(data),
        "source_pages": len(PdfReader(io.BytesIO(data)).pages),   # 시드의 source_document.page_count(ADR-0014)
        "model": model,
        "calls": len(calls),
        "stop_reason": "max_tokens" if any(c["stop_reason"] == "max_tokens" for c in calls) else calls[-1]["stop_reason"],
        "elapsed_sec": round(sum(c["elapsed_sec"] for c in calls), 1),
        "usage": {k: sum(c["usage"][k] for c in calls) for k in ("input_tokens", "output_tokens")},
        "cost_usd_est": round(sum(c["cost_usd_est"] for c in calls), 4),
        "parts": [{k: v for k, v in c.items() if k != "result"} for c in calls],
        "result": merged,
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("paths", nargs="+")
    ap.add_argument("--model", default="claude-sonnet-5-5")
    ap.add_argument("--max-tokens", type=int, default=20000)
    ap.add_argument("--lite", action="store_true", help="E1 채점용: 핵심 필드만 담은 schema_lite.json, 호출 1번")
    ap.add_argument("--out", default=str(HERE / "out"))
    a = ap.parse_args()

    load = lambda name: json.loads((HERE / name).read_text(encoding="utf-8"))
    parts = ([(load("schema_lite.json"), LITE)] if a.lite else
             [(load("schema_part_institution.json"), PART_INSTITUTION), (load("schema_part_jobs.json"), PART_JOBS)])
    system = (HERE / "prompt_system.md").read_text(encoding="utf-8")
    out = pathlib.Path(a.out); out.mkdir(parents=True, exist_ok=True)
    client = anthropic.Anthropic(timeout=600)

    pdfs = find_pdfs(a.paths)
    if not pdfs:
        sys.exit("운영계획서 PDF를 찾지 못했습니다.")
    total = 0.0
    for pdf in pdfs:
        print(f"→ {pdf.name} ({pdf.stat().st_size/1e6:.1f}MB) ...", flush=True)
        try:
            rec = extract(client, pdf, parts, system, a.model, a.max_tokens)
        except anthropic.BadRequestError as e:
            print(f"   [실패] 400: {e.message}")
            m = str(e.message).lower()
            if "complex" in m or "grammar is too large" in m:
                print("   → 스키마가 문법 한도를 넘었다는 오류입니다. build_schema.py 로 만든 파트 스키마인지 확인하세요.")
            continue
        except Exception as e:  # noqa: BLE001
            print(f"   [실패] {type(e).__name__}: {e}")
            continue
        (out / f"{pdf.stem}.json").write_text(json.dumps(rec, ensure_ascii=False, indent=2), encoding="utf-8")
        total += rec["cost_usd_est"]
        jobs = len(rec["result"]["jobs"]) if rec["result"] else "파싱 실패"
        warn = "  ⚠ max_tokens에서 잘림 — 문서를 쪽으로 나눠 다시 실행하세요" if rec["stop_reason"] == "max_tokens" else ""
        print(f"   완료 {rec['elapsed_sec']}s(호출 {rec['calls']}번), 입력 {rec['usage']['input_tokens']:,} / 출력 {rec['usage']['output_tokens']:,} 토큰,"
              f" 약 ${rec['cost_usd_est']}, 직무 {jobs}개{warn}")
    print(f"\n합계 약 ${total:.3f}. 다음: python score.py")


if __name__ == "__main__":
    main()
