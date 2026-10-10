"""E7 입력 만들기: 시드(seed.json)에서 자리 40개와 선배 수기 실습 내용을 실험 모양으로 뽑는다 (ADR-0031)

    python build_inputs.py [--seed ../seed/seed.json] [--out out]

- out/jobs.json: 직무 원문(부서·직무명·개요·교육 목표·요구 역량·주차 계획 '주차 내용')과 판정에 쓰던 학년 규칙·유형·선호 전공
- out/testimonials.json: 2026-2 참여기관에 이어지는 선배 수기의 실습 내용 + 실습 결과 구절(' / '로 이음) — S1 시험 세트
결과는 커밋하지 않는다(시드 사본). 표준 라이브러리만 쓴다.
"""
import argparse, json, pathlib

HERE = pathlib.Path(__file__).resolve().parent



def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--seed", default=str(HERE.parent / "seed" / "seed.json"))
    ap.add_argument("--out", default=str(HERE / "out"))
    a = ap.parse_args()
    s = json.loads(pathlib.Path(a.seed).read_text(encoding="utf-8"))
    out = pathlib.Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    inst = {i["id"]: i["name"] for i in s["institution"]}
    plans = {}
    for p in sorted(s["job_weekly_plan"], key=lambda p: (p["job_id"], p["seq"])):
        plans.setdefault(p["job_id"], []).append(f"{p['weeks_label']} {p['content']}")
    jobs = [{"id": j["id"], "inst": inst[j["institution_id"]], "inst_id": j["institution_id"], "team": j["team"],
             "title": j["title"], "overview": j["overview"] or "", "goal": j["education_goal"] or "",
             "comp": j["competencies"] or "", "plan": plans.get(j["id"], []), "grade_rule": j["grade_rule"],
             "job_type": j["job_type"], "major_text": j["major_text"]}
            for j in sorted(s["job"], key=lambda j: j["list_seq"])]
    by_id = {t["id"]: t for t in s["testimonial"]}
    ts = []
    for t in sorted(by_id.values(), key=lambda t: t["id"]):
        text = " / ".join((t.get("activities") or []) + (t.get("outcomes") or []))
        if text:
            ts.append({"tid": len(ts) + 1, "inst_id": t["institution_id"], "inst": inst[t["institution_id"]],
                       "team": t["team_text"], "text": text})
    (out / "jobs.json").write_text(json.dumps(jobs, ensure_ascii=False, indent=1), encoding="utf-8")
    (out / "testimonials.json").write_text(json.dumps(ts, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"자리 {len(jobs)} · 수기 {len(ts)} → {out}")


if __name__ == "__main__":
    main()
