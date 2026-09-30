"""E2 채점: 수기 추출 결과(out/*.json)의 건수, 중복, 2026-2 참여기관 연결 수를 확인하고 사람 판정표를 만든다.

사용법
  1) python score.py                      → e2_report.md, review_records.csv
  2) review_records.csv 의 '판정_' 열에 O/X 를 채운다(원본 PDF와 대조)
  3) python score.py --review review_records.csv
"""
import argparse, csv, json, pathlib, re, sys
from collections import Counter

HERE = pathlib.Path(__file__).parent

# 학기별 기대 건수: 1인 = 글 쪽 1 + 사진 쪽 1 이라서 쪽수/2 (2024-1 20쪽, 2024-2 20쪽, 2025-1 18쪽, 2025-2 20쪽 → 39명)
EXPECTED_PER_SEMESTER = {"2024-1": 10, "2024-2": 10, "2025-1": 9, "2025-2": 10}

# 2026-2 참여기관과 연결되는 수기(자체 분석 결과: 8곳 17건)
EXPECTED_LINKS = {"소서": 4, "델타텍코리아": 3, "위아프렌즈": 3, "스미스": 2, "오뷔엘알": 2,
                  "챔프스터디": 1, "더에스엠씨": 1, "빌딩닥터엔지니어링": 1}


def link_2026_2(institution, department):
    """수기에 적힌 기관(과거 명칭 포함)을 2026-2 참여기관으로 연결한다."""
    s = re.sub(r"\(주\)|㈜|주식회사|\s", "", institution or "")
    d = re.sub(r"\s", "", department or "")
    if "원오세븐" in s or "소서" in s: return "소서"
    if "세정" in s and "OL" in d.upper(): return "오뷔엘알"          # 세정 OL디자인팀 → 오뷔엘알
    if "오뷔엘알" in s or "올리비아로렌" in s: return "오뷔엘알"
    if "델타텍" in s: return "델타텍코리아"
    if "위아프렌즈" in s: return "위아프렌즈"
    if "챔프스터디" in s or "해커스" in s: return "챔프스터디"
    if "더에스엠씨" in s: return "더에스엠씨"
    if "빌딩닥터" in s: return "빌딩닥터엔지니어링"
    if s.startswith("스미스"): return "스미스"
    return ""


def semester_of(name):
    m = re.search(r"(20\d\d)-([12])", name)
    return f"{m.group(1)}-{m.group(2)}" if m else ""


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=str(HERE / "out"))
    ap.add_argument("--review", default=None)
    a = ap.parse_args()

    recs = [json.loads(p.read_text(encoding="utf-8")) for p in sorted(pathlib.Path(a.out).glob("*.json"))]
    if not recs:
        sys.exit("out/ 에 결과가 없습니다. run_extract.py 를 먼저 실행하세요.")

    lines, rows, links = ["# E2 참여수기 추출 결과", ""], [], Counter()
    # 한 학기 PDF를 나눠서 추출할 수도 있다(max_tokens 대처). 건수는 파일이 아니라 학기 단위로 센다.
    per_sem = Counter()
    for r in recs:
        res = r.get("result")
        sem = semester_of(r["source_file"])
        lines.append(f"## {r['source_file']}")
        if not res:
            lines += ["- **JSON 파싱 실패**", ""]; continue
        items = res["records"]
        per_sem[sem] += len(items)
        lines.append(f"- {r['elapsed_sec']}초, 입력 {r['usage']['input_tokens']:,} / 출력 {r['usage']['output_tokens']:,} 토큰, "
                     f"stop_reason={r['stop_reason']}, 약 ${r['cost_usd_est']}")
        lines.append(f"- 수기 {len(items)}건")
        pages = Counter(x["text_page"] for x in items)
        dup = [p for p, c in pages.items() if c > 1]
        if dup: lines.append(f"- ✗ 같은 글 쪽이 두 번 나옴: {dup}")
        empty = [i + 1 for i, x in enumerate(items) if not x["major"] or not x["institution"] or not x["activities"]]
        if empty: lines.append(f"- ✗ 기관·학과·실습내용이 빈 항목: {empty}번째")
        for i, x in enumerate(items, 1):
            link = link_2026_2(x["institution"], x["department"])
            if link: links[link] += 1
            rows.append({"파일": r["source_file"], "번호": i, "학기": x["semester"], "기관": x["institution"],
                         "부서": x["department"], "학과": x["major"], "학년": x["grade"],
                         "실습내용_항목수": len(x["activities"]), "글쪽": x["text_page"], "사진쪽": x["photo_page"],
                         "2026-2연결": link, "판정_기관부서": "", "판정_학과학년": "", "판정_실습내용": "", "메모": ""})
        lines.append("")

    total = sum(per_sem.values())
    expected_total = sum(EXPECTED_PER_SEMESTER.get(s, 0) for s in per_sem)
    summary = ["## 요약", "", f"- 수기 건수: **{total}건** (기대 {expected_total}건)"
               + ("" if total == expected_total else " ✗")]
    for sem in sorted(per_sem):
        exp = EXPECTED_PER_SEMESTER.get(sem)
        mark = "" if exp == per_sem[sem] else " ✗"
        summary.append(f"    - {sem}: {per_sem[sem]} / 기대 {exp if exp is not None else '?'}{mark}")
    got = sum(links.values())
    summary.append(f"- 2026-2 참여기관 연결: **{got}건** (기대 17건)")
    for k, v in EXPECTED_LINKS.items():
        mark = "" if links.get(k, 0) == v else " ✗"
        summary.append(f"    - {k}: {links.get(k, 0)} / 기대 {v}{mark}")
    if a.review:
        marks = list(csv.DictReader(open(a.review, encoding="utf-8-sig")))
        o = n = 0
        for c in ["판정_기관부서", "판정_학과학년", "판정_실습내용"]:
            vals = [m[c].strip().upper() for m in marks if m.get(c, "").strip()]
            o += sum(v == "O" for v in vals); n += len(vals)
        if n:
            summary.append(f"- 사람 판정: **{o}/{n} ({o / n * 100:.1f}%)** → {'합격' if o / n >= 0.9 else '불합격'} (기준 90%)")
    else:
        summary.append("- 사람 판정: review_records.csv 의 '판정_' 열을 채운 뒤 --review 로 다시 실행")
    summary.append("")

    (HERE / "e2_report.md").write_text("\n".join(lines[:2] + summary + lines[2:]), encoding="utf-8")
    if rows and not a.review:
        with open(HERE / "review_records.csv", "w", encoding="utf-8-sig", newline="") as f:
            w = csv.DictWriter(f, fieldnames=list(rows[0])); w.writeheader(); w.writerows(rows)
        print("wrote", HERE / "review_records.csv")
    print("wrote", HERE / "e2_report.md")
    print("\n".join(summary))


if __name__ == "__main__":
    main()
