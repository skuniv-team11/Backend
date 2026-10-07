"""seed.json → src/main/resources/db/migration/R__seed.sql (Flyway 반복 마이그레이션, ADR-0014).

    python to_sql.py            # 다시 만든다
    python to_sql.py --check    # 파일이 seed.json과 맞는지만 본다(check_pipeline.py가 부른다)

다시 시드해도 사용자 데이터가 같은 행을 가리키게(ADR-0009):
- 부모 테이블(program·recruit_round·department·area·certificate·institution·workplace·job)은 id(area·certificate는
  code)로 upsert 하고, seed에 없는 행만 지운다. job을 지우면 담아 둔 지망(plan_item)도 함께 지워진다(직무가 없어졌으므로).
  학생 프로필이 쓰는 학과·사는 곳·자격증은 seed에서 빠져도 지우지 않는다.
- 자식 테이블(근거·알림·판정 출처·수기·신호·전공 표기·가까운 학과 묶음)은 통째로 지우고 다시 넣는다. 사용자 데이터가 가리키지 않는다.
- job_embedding(E5)·round_result(센터 동의 뒤 로컬 적재)는 건드리지 않는다.
Flyway는 이 파일의 checksum이 바뀔 때마다 V* 다음에 한 트랜잭션으로 다시 적용한다.
"""
import argparse, datetime as dt, hashlib, json, pathlib, sys

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parent.parent
SEED = HERE / "seed.json"
OUT = ROOT / "src" / "main" / "resources" / "db" / "migration" / "R__seed.sql"

# 테이블별 열 순서(V1__init.sql과 같다). seed.json에 다른 키가 있으면 실패한다.
PARENTS = {
    "program": ["id", "code", "name"],
    "recruit_round": ["id", "program_id", "term_code", "round_no", "recruit_start", "recruit_end"],
    "department": ["id", "name", "college", "enrolled_count", "enrolled_as_of"],
    "area": ["code", "sido", "name", "sort_order"],
    "certificate": ["code", "label", "sort_order"],
    "institution": ["id", "name", "size", "listing", "business_type", "business_item", "address",
                    "nts_status", "nts_checked_on"],
    "workplace": ["id", "institution_id", "address"],
    "job": ["id", "round_id", "institution_id", "workplace_id", "list_seq", "team", "title", "overview",
            "education_goal", "competencies", "course", "job_type", "period_start", "period_end",
            "work_hours_text", "weekly_hours", "weekdays", "overtime", "labor_contract", "stipend_basis",
            "stipend_amount", "benefits", "headcount", "grade_rule", "gpa_min", "portfolio", "certificate",
            "certificate_code", "certificate_text", "major_text", "major_open", "closes_on", "close_reason",
            "closes_on_is_virtual", "final_assigned"],
}
CHILDREN = {
    "major_alias": ["id", "label"],
    "major_alias_department": ["alias_id", "department_id"],
    "department_cluster": ["id", "label"],
    "department_cluster_member": ["cluster_id", "department_id"],
    "job_major_alias": ["job_id", "alias_id"],
    "job_weekly_plan": ["job_id", "seq", "weeks_label", "content"],
    "source_document": ["id", "kind", "title", "term_code", "institution_id", "page_count"],
    "field_evidence": ["id", "source_document_id", "institution_id", "job_id", "field_key", "raw_value",
                       "page", "quote"],
    "review_alert": ["id", "institution_id", "job_id", "kind", "field_key", "description",
                     "source_document_id", "page_a", "quote_a", "page_b", "quote_b"],
    "requirement_source": ["job_id", "item", "source_type", "document_title", "page", "quote"],
    "testimonial": ["id", "source_document_id", "institution_id", "team_text", "activities", "outcomes", "page"],
    "replay_signal": ["job_id", "signal_date", "interest_count"],
}
# upsert 키(기본은 id)
KEYS = {"area": "code", "certificate": "code"}
ARRAYS = {("job", "weekdays"): "varchar(3)[]", ("job", "benefits"): "varchar(20)[]",
          ("testimonial", "activities"): "text[]", ("testimonial", "outcomes"): "text[]"}
DATES = {("recruit_round", "recruit_start"), ("recruit_round", "recruit_end"),
         ("department", "enrolled_as_of"), ("institution", "nts_checked_on"), ("job", "period_start"),
         ("job", "period_end"), ("job", "closes_on"), ("replay_signal", "signal_date")}
# 지울 때는 FK를 거꾸로 따라간다
DELETE_CHILDREN = ["replay_signal", "testimonial", "requirement_source", "review_alert", "field_evidence", "source_document",
                   "job_weekly_plan", "job_major_alias", "major_alias_department", "major_alias",
                   "department_cluster_member", "department_cluster"]
CHUNK = 200


def lit(table, col, v):
    if (table, col) in ARRAYS:
        typ = ARRAYS[(table, col)]
        if not isinstance(v, list):
            raise ValueError(f"{table}.{col}: 배열이어야 함 — {v!r}")
        return f"ARRAY[{', '.join(lit(table, '', x) for x in v)}]::{typ}" if v else f"'{{}}'::{typ}"
    if v is None:
        return "NULL"
    if isinstance(v, bool):
        return "TRUE" if v else "FALSE"
    if isinstance(v, int):
        return str(v)
    if isinstance(v, float):
        return format(v, "f").rstrip("0").rstrip(".") if v != int(v) else f"{int(v)}.0"
    if isinstance(v, str):
        if "\r" in v:
            raise ValueError(f"{table}.{col}: 줄바꿈은 \\n만 쓴다(build_seed가 정리) — {v[:60]!r}")
        if "${" in v:
            raise ValueError(f"{table}.{col}: Flyway 자리표시자로 읽히는 '${{' 가 있음 — {v[:60]!r}")
        if (table, col) in DATES:
            dt.date.fromisoformat(v)
            return f"DATE '{v}'"
        return "'" + v.replace("'", "''") + "'"
    raise ValueError(f"{table}.{col}: 다룰 수 없는 값 {v!r}")


def inserts(table, cols, rows, upsert):
    key = KEYS.get(table, "id")
    out = []
    for i in range(0, len(rows), CHUNK):
        part = rows[i:i + CHUNK]
        values = ",\n".join("  (" + ", ".join(lit(table, c, r[c]) for c in cols) + ")" for r in part)
        sql = f"INSERT INTO {table} ({', '.join(cols)}) VALUES\n{values}"
        if upsert:
            sets = ", ".join(f"{c} = EXCLUDED.{c}" for c in cols if c != key)
            sql += f"\nON CONFLICT ({key}) DO UPDATE SET {sets}"
        out.append(sql + ";")
    return out


def ids(rows):
    return ", ".join(str(r["id"]) for r in rows) or "NULL"


def codes(rows):
    return ", ".join("'" + r["code"] + "'" for r in rows) or "NULL"


def render(seed):
    for t, cols in {**PARENTS, **CHILDREN}.items():
        for r in seed.get(t, []):
            if set(r) != set(cols):
                raise ValueError(f"{t}: 열이 다름 — 남는 것 {sorted(set(r) - set(cols))}, 빠진 것 {sorted(set(cols) - set(r))}")
    p = {t: seed.get(t, []) for t in PARENTS}
    s = []
    s.append("-- 1. 자식 테이블은 통째로 지운다(다시 넣는다)")
    s += [f"DELETE FROM {t};" for t in DELETE_CHILDREN]
    s.append("\n-- 2. 부모 테이블: seed에 없는 행을 지운다(job → plan_item cascade). 학생 프로필이 쓰는 학과는 남긴다")
    s.append(f"DELETE FROM job WHERE id NOT IN ({ids(p['job'])});")
    s.append(f"DELETE FROM workplace WHERE id NOT IN ({ids(p['workplace'])});")
    s.append(f"DELETE FROM institution WHERE id NOT IN ({ids(p['institution'])});")
    s.append(f"DELETE FROM department d WHERE d.id NOT IN ({ids(p['department'])})\n"
             f"  AND NOT EXISTS (SELECT 1 FROM student_profile sp WHERE sp.department_id = d.id);")
    s.append(f"DELETE FROM area a WHERE a.code NOT IN ({codes(p['area'])})\n"
             f"  AND NOT EXISTS (SELECT 1 FROM student_profile sp WHERE sp.home_area_code = a.code);")
    s.append("\n-- 3. 유니크 열을 잠시 비켜 둔다(이름·순번이 행끼리 바뀌어도 upsert가 부딪히지 않게)")
    s.append("UPDATE institution SET name = '#' || id;")
    s.append("UPDATE workplace SET address = '#' || id;")
    s.append("UPDATE job SET list_seq = list_seq + 10000;")
    s.append(f"UPDATE department SET name = '#' || id WHERE id IN ({ids(p['department'])});")
    s.append("UPDATE area SET sort_order = -sort_order;")
    s.append(f"UPDATE area SET name = '#' || code WHERE code IN ({codes(p['area'])});")
    s.append("\n-- 4. 부모 테이블 upsert(id 고정)")
    for t, cols in PARENTS.items():
        if p[t]:
            s.append(f"\n-- {t} {len(p[t])}행")
            s += inserts(t, cols, p[t], upsert=True)
    s.append("\n-- 자격증: seed에서 빠진 코드는 직무 upsert 뒤에 지운다(FK). 프로필이 쓰는 코드는 남긴다(ADR-0021)")
    s.append(f"DELETE FROM certificate c WHERE c.code NOT IN ({codes(p['certificate'])})\n"
             f"  AND NOT EXISTS (SELECT 1 FROM job j WHERE j.certificate_code = c.code)\n"
             f"  AND NOT EXISTS (SELECT 1 FROM student_profile sp WHERE c.code = ANY (sp.certificates));")
    s.append("\n-- 사는 곳: seed에서 빠졌지만 프로필이 쓰고 있어 남은 행은 목록 맨 뒤로")
    s.append("UPDATE area a SET sort_order = 30000 + s.n\n"
             "  FROM (SELECT code, row_number() OVER (ORDER BY code) AS n FROM area WHERE sort_order < 0) s\n"
             "  WHERE a.code = s.code;")
    s.append("\n-- 5. 자식 테이블")
    for t, cols in CHILDREN.items():
        rows = seed.get(t, [])
        if rows:
            s.append(f"\n-- {t} {len(rows)}행")
            s += inserts(t, cols, rows, upsert=False)
    return "\n".join(s) + "\n"


def build(seed_text):
    body = render(json.loads(seed_text))
    head = ("-- R__seed.sql — 시드 데이터(Flyway 반복 마이그레이션, ADR-0014). 손으로 고치지 않는다.\n"
            "-- 만드는 법: python pipeline/seed/build_seed.py ... → python pipeline/seed/to_sql.py (pipeline/seed/README.md)\n"
            f"-- seed.json sha256: {hashlib.sha256(seed_text.encode('utf-8')).hexdigest()}\n"
            f"-- body sha256: {hashlib.sha256(body.encode('utf-8')).hexdigest()}\n\n")
    return head + body


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--seed", default=str(SEED))
    ap.add_argument("--out", default=str(OUT))
    ap.add_argument("--check", action="store_true")
    a = ap.parse_args()
    # Windows 체크아웃(CRLF)에서도 같은 해시가 나오게 줄바꿈을 LF로 맞춘다(.gitattributes도 eol=lf)
    seed_text = pathlib.Path(a.seed).read_text(encoding="utf-8").replace("\r\n", "\n")
    sql = build(seed_text)
    out = pathlib.Path(a.out)
    if a.check:
        if not out.exists() or out.read_text(encoding="utf-8").replace("\r\n", "\n") != sql:
            print(f"✗ {out.name}이 seed.json과 다릅니다\n  → python pipeline/seed/to_sql.py 로 다시 만들어 같이 커밋하세요")
            sys.exit(1)
        print(f"✓ {out.name}이 seed.json과 같습니다")
        return
    out.write_text(sql, encoding="utf-8", newline="\n")
    counts = {t: len(json.loads(seed_text).get(t, [])) for t in {**PARENTS, **CHILDREN}}
    print(f"→ {out} ({len(sql.encode('utf-8')) / 1024:.0f}KB) " + ", ".join(f"{t} {n}" for t, n in counts.items() if n))


if __name__ == "__main__":
    main()
