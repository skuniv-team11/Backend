"""E1 채점: out/*.json 을 기관 기준표(정답셋)와 비교하고, 근거 인용문이 원문에 실제로 있는지 확인한다.

사용법
  1) python score.py --pdf-root "<운영계획서 폴더>"
       → e1_report.md, review_jobs.csv 생성
  2) review_jobs.csv 를 엑셀로 열어 '판정_' 열에 O/X 를 채우고 저장
  3) python score.py --pdf-root "<운영계획서 폴더>" --review review_jobs.csv
       → 직무 단위 사람 판정까지 합쳐 e1_report.md 다시 생성

정답셋(기관 기준표)도 사람이 옮긴 초안이라 틀릴 수 있다. 불일치가 나오면 원본 PDF를 보고
'AI가 틀림'인지 '정답셋이 틀림'인지 판단해 보고서의 메모에 적는다.
"""
import argparse, csv, json, pathlib, re, sys
from collections import defaultdict

HERE = pathlib.Path(__file__).parent

# ---------- 정규화 ----------
def digits(s): return re.sub(r"\D", "", s or "")
def squash(s): return re.sub(r"\s+", "", s or "")
def norm_name(s): return re.sub(r"\(주\)|㈜|주식회사|\s", "", s or "")

def norm_date(s):
    n = re.findall(r"\d+", s or "")
    if len(n) < 3: return ""
    y, m, d = int(n[0]), int(n[1]), int(n[2])
    return f"{y:04d}-{m:02d}-{d:02d}"

def headcount(s):
    m = re.findall(r"(\d+)\s*명", s or "")
    if m: return int(m[-1])
    d = digits(s)
    return int(d) if d else None

SIZE = {"대기업": "대", "중견기업": "중견", "중소기업": "중소", "공공기관": "공공", "협회/기타": "기타"}
LISTING = {"코스닥 표기": "코스닥"}
OVERTIME = {"상황별 실시": "상황별", "주기적/상시적 실시": "주기적"}


# ---------- PDF 쪽 텍스트 (인용문 확인용) ----------
_page_cache = {}
def page_texts(pdf_path):
    if pdf_path not in _page_cache:
        from pypdf import PdfReader
        try:
            _page_cache[pdf_path] = [squash(p.extract_text() or "") for p in PdfReader(str(pdf_path)).pages]
        except Exception as e:  # noqa: BLE001
            print(f"[경고] PDF 텍스트 추출 실패 {pdf_path.name}: {e}")
            _page_cache[pdf_path] = []
    return _page_cache[pdf_path]

def verify_quote(pdf_path, page, quote):
    """반환: 일치 / 쪽 틀림(N쪽에 있음) / 원문에 없음 / 이미지 쪽(사람 확인) / 근거 없음"""
    if not quote or page <= 0: return "근거 없음"
    if pdf_path is None: return "PDF 없음"
    pages = page_texts(pdf_path)
    if not pages: return "PDF 없음"
    q = squash(quote)
    if 1 <= page <= len(pages):
        if len(pages[page - 1]) < 30: return "이미지 쪽(사람 확인)"
        if q in pages[page - 1]: return "일치"
    for i, t in enumerate(pages, 1):
        if q and q in t: return f"쪽 틀림({i}쪽에 있음)"
    return "원문에 없음"

def iter_sourced(node, path=""):
    """value/page/quote 를 가진 모든 필드를 (경로, 필드) 로 돌려준다"""
    if isinstance(node, dict):
        if {"value", "page", "quote"} <= set(node):
            yield path, node
        else:
            for k, v in node.items():
                yield from iter_sourced(v, f"{path}.{k}" if path else k)
    elif isinstance(node, list):
        for i, v in enumerate(node):
            yield from iter_sourced(v, f"{path}[{i}]")


# ---------- 기관 단위 자동 비교 ----------
def compare_institution(res, ans):
    inst, jobs = res["institution"], res["jobs"]
    v = lambda k: inst[k]["value"]
    rows = []
    def add(field, got, exp, ok):
        rows.append({"항목": field, "추출": got, "정답셋": exp, "결과": "일치" if ok else "불일치"})

    add("사업자등록번호", v("business_no"), ans["사업자등록번호(계획서)"],
        digits(v("business_no")) == digits(ans["사업자등록번호(계획서)"]))
    add("개업일", v("opened_on"), ans["개업일"], norm_date(v("opened_on")) == ans["개업일"])
    codes = {digits(c) for c in ans["산업분류코드"].split("/")}
    add("산업분류코드", v("ksic_code"), ans["산업분류코드"], digits(v("ksic_code")) in codes)
    add("종업원 수", v("employees"), ans["종업원수(계획서)"], digits(v("employees")) == digits(ans["종업원수(계획서)"]))
    add("규모", v("size"), ans["규모"], SIZE.get(v("size"), v("size")) == ans["규모"])
    exp_listing = LISTING.get(ans["상장"], ans["상장"])
    add("상장", v("listing"), ans["상장"], v("listing") == exp_listing)

    types = sorted({j["job_type"]["value"] for j in jobs})
    add("운영유형(직무 전체)", ", ".join(types), ans["운영유형"], types == [ans["운영유형"]])
    ot = sorted({OVERTIME.get(j["overtime"]["value"], j["overtime"]["value"]) for j in jobs})
    add("연장실습(직무 전체)", ", ".join(ot), ans["연장실습"], ot == [ans["연장실습"]])
    lc = sorted({j["labor_contract"]["value"] for j in jobs})
    add("근로계약(직무 전체)", ", ".join(lc), ans["근로계약"], lc == [ans["근로계약"]])
    amounts = sorted({digits(j["stipend_amount"]["value"]) for j in jobs})
    add("실습지원비(직무 전체)", ", ".join(amounts), ans["실습지원비_계획서"], amounts == [ans["실습지원비_계획서"]])
    meal = "O" if any("식사" in j["benefits"]["value"] for j in jobs) else "X"
    add("식사지원", meal, ans["식사지원"], meal == ans["식사지원"])
    add("직무 수", str(len(jobs)), ans["직무수"], str(len(jobs)) == ans["직무수"])
    hc = [headcount(j["headcount"]["value"]) for j in jobs]
    total = sum(h for h in hc if h) if all(h is not None for h in hc) else None
    add("모집인원 합계", "파싱 실패" if total is None else str(total), ans["모집인원"],
        total is not None and str(total) == ans["모집인원"])
    return rows


# 문서 내부 불일치: 기획안에 적힌 실제 사례를 잡는지 (M2 참고 지표)
EXPECTED_INCONSISTENCY = {
    "1198682412": ("선도소프트: 교육목표 '컴퓨터공학' vs 전공 '소프트웨어학과'", ["컴퓨터", "소프트웨어"], all),
    "1208646551": ("비욘드마케팅그룹: 산업분류코드가 쪽마다 다름", ["743002", "71310"], any),
    "1208709984": ("챔프스터디: 실습기간 끝 연도 2025", ["2025"], any),
}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=str(HERE / "out"))
    ap.add_argument("--answer", default=str(HERE / "data" / "answer_institutions.csv"))
    ap.add_argument("--pdf-root", default=None, help="원본 PDF 폴더(인용문 확인용)")
    ap.add_argument("--review", default=None, help="판정을 채운 review_jobs.csv")
    a = ap.parse_args()

    answers = list(csv.DictReader(open(a.answer, encoding="utf-8-sig")))
    by_bno = {digits(x["사업자등록번호(계획서)"]): x for x in answers}
    by_name = {norm_name(x["기관"]): x for x in answers}
    pdf_index = {}
    if a.pdf_root:
        for p in pathlib.Path(a.pdf_root).rglob("*.pdf"):
            pdf_index[p.name] = p

    recs = [json.loads(p.read_text(encoding="utf-8")) for p in sorted(pathlib.Path(a.out).glob("*.json"))]
    if not recs:
        sys.exit("out/ 에 결과가 없습니다. run_extract.py 를 먼저 실행하세요.")

    lines = ["# E1 운영계획서 추출 결과", ""]
    auto_ok = auto_n = 0
    qstat = defaultdict(int)
    review_rows = []
    field_miss = defaultdict(int)
    cost = sum(r.get("cost_usd_est", 0) for r in recs)
    lines.append(f"- 문서 {len(recs)}건, 예상 비용 약 ${cost:.3f}, 모델 {recs[0].get('model')}")
    lines.append("")

    for r in recs:
        res = r.get("result")
        lines.append(f"## {r['source_file']}")
        if not res:
            lines.append("- **JSON 파싱 실패** — raw_text_if_unparsed 확인"); lines.append(""); continue
        lines.append(f"- {r['elapsed_sec']}초, 입력 {r['usage']['input_tokens']:,} / 출력 {r['usage']['output_tokens']:,} 토큰, stop_reason={r['stop_reason']}")
        pdf = pdf_index.get(r["source_file"])

        # 인용문 확인
        doc_q = defaultdict(int)
        bad_quotes = []
        for path, f in iter_sourced(res):
            st = verify_quote(pdf, f["page"], f["quote"])
            key = st.split("(")[0]
            doc_q[key] += 1; qstat[key] += 1
            if key in ("쪽 틀림", "원문에 없음"):
                bad_quotes.append(f"`{path}` {st}: {f['quote'][:40]}")
        lines.append("- 인용문 확인: " + ", ".join(f"{k} {v}" for k, v in sorted(doc_q.items())))
        for b in bad_quotes[:10]:
            lines.append(f"    - {b}")

        # 기관 단위 자동 비교
        inst = res["institution"]
        ans = by_bno.get(digits(inst["business_no"]["value"])) or by_name.get(norm_name(inst["name"]["value"]))
        if not ans:
            lines.append("- 정답셋에서 기관을 찾지 못함(사업자번호·기관명 확인)")
        else:
            rows = compare_institution(res, ans)
            ok = sum(x["결과"] == "일치" for x in rows); auto_ok += ok; auto_n += len(rows)
            lines.append(f"- 기관 단위 자동 비교: {ok}/{len(rows)} 일치")
            for x in rows:
                if x["결과"] != "일치":
                    field_miss[x["항목"]] += 1
                    lines.append(f"    - ✗ {x['항목']}: 추출 `{x['추출']}` / 정답셋 `{x['정답셋']}`")
            exp = EXPECTED_INCONSISTENCY.get(digits(ans["사업자등록번호(계획서)"]))
            if exp:
                blob = json.dumps(res["inconsistencies"], ensure_ascii=False)
                hit = exp[2](k in blob for k in exp[1])
                lines.append(f"- 알려진 불일치 탐지: {'잡음' if hit else '못 잡음'} — {exp[0]}")
        if res["inconsistencies"]:
            lines.append("- 모델이 기록한 문서 내부 불일치:")
            for x in res["inconsistencies"]:
                lines.append(f"    - {x['description']} ({x['page_a']}쪽 ↔ {x['page_b']}쪽)")

        # 직무 단위 사람 판정용 행
        for i, j in enumerate(res["jobs"], 1):
            def cell(k): return j[k]["value"] if isinstance(j[k]["value"], str) else ", ".join(j[k]["value"])
            review_rows.append({
                "문서": r["source_file"], "직무번호": i,
                "부서": cell("department"), "직무명": cell("job_title"),
                "전공요건": cell("major_requirement"), "전공_쪽": j["major_requirement"]["page"],
                "전공_인용확인": verify_quote(pdf, j["major_requirement"]["page"], j["major_requirement"]["quote"]),
                "인원": cell("headcount"), "학년": cell("grade_requirement"), "학점": cell("gpa_requirement"),
                "요일": cell("weekdays"), "시간": cell("hours"), "기간": cell("period"),
                "포트폴리오": cell("portfolio"),
                "판정_전공": "", "판정_인원": "", "판정_학년": "", "판정_학점": "", "판정_요일시간": "", "메모": "",
            })
        lines.append("")

    # 요약
    summary = ["## 요약", ""]
    summary.append(f"- 기관 단위 자동 비교: **{auto_ok}/{auto_n} ({auto_ok / auto_n * 100:.1f}%)**" if auto_n else "- 기관 단위 자동 비교: 없음")
    if field_miss:
        summary.append("    - 불일치가 난 항목: " + ", ".join(f"{k} {v}건" for k, v in sorted(field_miss.items(), key=lambda x: -x[1])))
    qt = sum(qstat.values())
    if qt:
        summary.append("- 인용문 확인(전체 필드): " + ", ".join(f"{k} {v}" for k, v in sorted(qstat.items())))
        checkable = qstat["일치"] + qstat["쪽 틀림"] + qstat["원문에 없음"]
        if checkable:
            summary.append(f"    - 텍스트 쪽에서 쪽 번호까지 맞은 비율: **{qstat['일치'] / checkable * 100:.1f}%** ({qstat['일치']}/{checkable})")

    # 사람 판정
    if a.review:
        marks = list(csv.DictReader(open(a.review, encoding="utf-8-sig")))
        cols = ["판정_전공", "판정_인원", "판정_학년", "판정_학점", "판정_요일시간"]
        tot_o = tot_n = 0
        parts = []
        for c in cols:
            vals = [m[c].strip().upper() for m in marks if m.get(c, "").strip()]
            o = sum(v == "O" for v in vals); n = len(vals)
            tot_o += o; tot_n += n
            if n: parts.append(f"{c[3:]} {o}/{n}")
        if tot_n:
            summary.append(f"- 직무 단위 사람 판정: **{tot_o}/{tot_n} ({tot_o / tot_n * 100:.1f}%)** — " + ", ".join(parts))
            all_o = auto_ok + tot_o; all_n = auto_n + tot_n
            verdict = "합격" if all_o / all_n >= 0.9 else "불합격 — 틀린 필드는 규칙 추출·수동 보정 대상으로 표시"
            summary.append(f"- **E1 판정(자동+사람, 기준 90%)**: {all_o}/{all_n} = {all_o / all_n * 100:.1f}% → {verdict}")
    else:
        summary.append("- 직무 단위 사람 판정: review_jobs.csv 의 '판정_' 열을 채운 뒤 --review 로 다시 실행")
    summary.append("")

    report = HERE / "e1_report.md"
    report.write_text("\n".join(lines[:3] + summary + lines[3:]), encoding="utf-8")
    if review_rows and not a.review:
        with open(HERE / "review_jobs.csv", "w", encoding="utf-8-sig", newline="") as f:
            w = csv.DictWriter(f, fieldnames=list(review_rows[0]))
            w.writeheader(); w.writerows(review_rows)
        print("wrote", HERE / "review_jobs.csv")
    print("wrote", report)
    print("\n".join(summary))


if __name__ == "__main__":
    main()
