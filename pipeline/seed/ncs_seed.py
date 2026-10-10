"""NCS 시드 만들기: 직무 → NCS 세분류(사람이 고른 것) · 능력단위 · 넓혀 갈 세분류 · 이어지는 직업 → ncs.json (ADR-0032)

    python ncs_seed.py --units ../e6_external/ncs_units.csv --keco <직업능력_코드매핑정보.csv>
    python to_sql.py                                   # seed.json + ncs.json → R__seed.sql

입력
- e6 `ncs_units.csv`: 한국산업인력공단 NCS 능력단위 전체(2026-09-30 적재, 15,520건). '(구버전)' 단위는 뺀다. 같은 세분류 안에서
  이름이 같은 단위(띄어쓰기·가운뎃점만 다름)는 개정 연도가 가장 늦은 것 하나만 둔다. 수준이 0(원본에 없음)이면 null.
- 한국고용정보원 '직업능력_코드매핑정보'(2025-11-26, 공공데이터포털 15154290): NCS 소분류(6자리)·세분류(8자리) ↔ 한국고용직업분류(KECO)
- curated/job_ncs.csv: 직무마다 세분류 하나(사람이 고름 — 직무 원문과 능력단위를 보고)
- curated/ncs_expand.csv: 세분류마다 넓혀 갈 세분류 3개(사람이 고름)
- curated/ncs_occupations.csv: 공식 연계표에 없는 세분류의 직업 추가(ADD)·소분류 전체에 붙어 엉뚱한 직업 빼기(DROP). 직업은 공식 표에 있는 것만

결과(seed/ncs.json, 커밋): ncs_subcategory · ncs_unit · occupation · ncs_occupation · job_ncs · ncs_expand.
능력단위는 직무에 고른 세분류와 넓혀 갈 세분류 것만 넣는다. 원본 CSV는 저장소 밖에 둔다.
"""
import argparse, csv, json, pathlib, re, sys
from collections import defaultdict

HERE = pathlib.Path(__file__).resolve().parent
CURATED = HERE / "curated"
UNIT_CODE = re.compile(r"^(\d{8})(\d{2})_(\d{2})v(\d+)$")


def norm(s):
    return re.sub(r"[\s·・]+", "", s or "")


def read_units(path):
    """세분류 코드 → {이름들, 단위 목록(정리 후)}."""
    subs, raw = {}, defaultdict(list)
    with open(path, encoding="utf-8-sig") as f:
        for r in csv.DictReader(f):
            m = UNIT_CODE.match(r["ncsClCd"])
            if not m:
                sys.exit(f"능력단위 코드 형식이 다름: {r['ncsClCd']}")
            sub = m.group(1)
            subs.setdefault(sub, {"code": sub, "large_name": r["ncsLclasCdnm"], "middle_name": r["ncsMclasCdnm"],
                                  "small_code": sub[:6], "small_name": r["ncsSclasCdnm"], "name": r["ncsSubdCdnm"]})
            if "(구버전)" in r["compeUnitName"]:
                continue
            raw[sub].append((int(m.group(3)), int(m.group(2)), r))
    units = {}
    for sub, rows in raw.items():
        best = {}
        for year, seq, r in rows:
            key = norm(r["compeUnitName"])
            if key not in best or (year, seq) > best[key][:2]:
                best[key] = (year, seq, r)
        units[sub] = [{"code": r["ncsClCd"], "subcategory_code": sub, "seq": seq, "name": r["compeUnitName"].strip(),
                       "level": int(r["compeUnitLevel"]) or None, "definition": (r["compeUnitDef"] or "").strip() or None}
                      for _, seq, r in sorted(best.values(), key=lambda x: x[1])]
    return subs, units


def read_keco(path):
    """NCS 코드(6·8자리) → {KECO 코드}, KECO 코드 → 이름."""
    links, names = defaultdict(set), {}
    with open(path, encoding="utf-8-sig") as f:
        rows = list(csv.reader(f))
    if rows[0][:6] != ["NCS 코드", "NCS 코드명", "국가기간직종 코드", "국가기간직종 코드명", "KECO 코드", "KECO 코드명"]:
        sys.exit(f"직업능력 코드매핑정보 머리글이 다름: {rows[0]}")
    for ncs, _, _, _, keco, keco_name in (r[:6] for r in rows[1:]):
        if keco.strip():
            links[ncs.strip()].add(keco.strip())
            names[keco.strip()] = keco_name.strip()
    return links, names


def read_csv(name):
    with open(CURATED / name, encoding="utf-8") as f:
        return list(csv.DictReader(f))


def build(units_path, keco_path, seed):
    subs, units = read_units(units_path)
    links, keco_names = read_keco(keco_path)
    fails = []
    job_ids = {j["id"] for j in seed["job"]}

    job_ncs = []
    for r in read_csv("job_ncs.csv"):
        code, jid = r["ncs_code"], int(r["job_id"])
        if jid not in job_ids:
            fails.append(f"job_ncs.csv: 시드에 없는 직무 {jid}")
        if code not in subs:
            fails.append(f"job_ncs.csv {jid}: 없는 세분류 {code}")
        elif subs[code]["name"] != r["ncs_name"]:
            fails.append(f"job_ncs.csv {jid}: {code}의 이름은 '{subs[code]['name']}'(적힌 이름 '{r['ncs_name']}')")
        elif not units.get(code):
            fails.append(f"job_ncs.csv {jid}: {code}에 능력단위가 없음")
        job_ncs.append({"job_id": jid, "subcategory_code": code, "note": r["note"].strip() or None})
    missing = job_ids - {j["job_id"] for j in job_ncs}
    if missing:
        fails.append(f"job_ncs.csv: 세분류를 안 고른 직무 {sorted(missing)}")
    mapped = sorted({j["subcategory_code"] for j in job_ncs})

    expand = []
    for r in read_csv("ncs_expand.csv"):
        a, b = r["from_code"], r["to_code"]
        if a not in mapped:
            fails.append(f"ncs_expand.csv: 직무에 고르지 않은 세분류에서 넓힘 {a}")
        if b not in subs or not units.get(b):
            fails.append(f"ncs_expand.csv {a}: 없거나 능력단위가 없는 세분류 {b}")
            continue
        if a == b:
            fails.append(f"ncs_expand.csv {a}: 자기 자신")
        relation = "SAME_SMALL" if a[:6] == b[:6] else "SAME_MIDDLE" if a[:4] == b[:4] else "OTHER"
        expand.append({"from_code": a, "rank": int(r["rank"]), "to_code": b, "relation": relation})
    for code in mapped:
        ranks = sorted(e["rank"] for e in expand if e["from_code"] == code)
        if ranks != [1, 2, 3]:
            fails.append(f"ncs_expand.csv {code}: 순위가 1·2·3이 아님 {ranks}")

    # 이어지는 직업: 공식 연계표(세분류 8자리 + 그 소분류 6자리) ± 사람이 고친 것
    occ = {code: links.get(code, set()) | links.get(code[:6], set()) for code in mapped}
    source = {(code, k): "KEIS" for code, ks in occ.items() for k in ks}
    for r in read_csv("ncs_occupations.csv"):
        code, k, action = r["ncs_code"], r["keco_code"], r["action"]
        if code not in mapped:
            fails.append(f"ncs_occupations.csv: 직무에 고르지 않은 세분류 {code}")
            continue
        if k not in keco_names:
            fails.append(f"ncs_occupations.csv {code}: 공식 표에 없는 직업 코드 {k}")
            continue
        if action == "ADD":
            if k in occ[code]:
                fails.append(f"ncs_occupations.csv {code}: 이미 연계표에 있는 직업 {k}")
            occ[code].add(k)
            source[(code, k)] = "CURATED"
        elif action == "DROP":
            if k not in occ[code]:
                fails.append(f"ncs_occupations.csv {code}: 연계표에 없는 직업을 빼려 함 {k}")
            occ[code].discard(k)
        else:
            fails.append(f"ncs_occupations.csv {code}: action은 ADD·DROP — {action}")
    if fails:
        sys.exit("ncs 시드를 만들 수 없습니다:\n  " + "\n  ".join(fails))

    needed = sorted(set(mapped) | {e["to_code"] for e in expand})
    used_keco = sorted({k for ks in occ.values() for k in ks})
    return {
        "ncs_subcategory": [subs[c] for c in needed],
        "ncs_unit": [u for c in needed for u in units[c]],
        "occupation": [{"code": k, "name": keco_names[k]} for k in used_keco],
        "ncs_occupation": [{"subcategory_code": c, "occupation_code": k, "source": source[(c, k)]}
                           for c in mapped for k in sorted(occ[c])],
        "job_ncs": sorted(job_ncs, key=lambda j: j["job_id"]),
        "ncs_expand": sorted(expand, key=lambda e: (e["from_code"], e["rank"])),
    }


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--units", required=True, help="e6 ncs_units.csv")
    ap.add_argument("--keco", required=True, help="한국고용정보원_직업능력_코드매핑정보 CSV")
    ap.add_argument("--seed", default=str(HERE / "seed.json"))
    ap.add_argument("--out", default=str(HERE / "ncs.json"))
    a = ap.parse_args()
    seed = json.loads(pathlib.Path(a.seed).read_text(encoding="utf-8"))
    out = build(a.units, a.keco, seed)
    pathlib.Path(a.out).write_text(json.dumps(out, ensure_ascii=False, indent=1) + "\n", encoding="utf-8", newline="\n")
    print(f"→ {a.out}: " + ", ".join(f"{k} {len(v)}" for k, v in out.items()))


if __name__ == "__main__":
    main()
