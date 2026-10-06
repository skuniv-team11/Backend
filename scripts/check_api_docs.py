"""docs/api 예시 검증: 파싱, README 링크, 코드값↔DDL, 계산값, 판정 규칙, 이름 규칙."""
import json, re, pathlib, sys

# 저장소 루트에서: python scripts/check_api_docs.py
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
ROOT = pathlib.Path(__file__).resolve().parent.parent
API = ROOT / "docs" / "api"
DDL = (ROOT / "src" / "main" / "resources" / "db" / "migration" / "V1__init.sql").read_text(encoding="utf-8")
fails, checks = [], 0

def check(cond, msg):
    global checks
    checks += 1
    if not cond:
        fails.append(msg)

docs = {}
for p in sorted(API.glob("*.json")):
    try:
        docs[p.name] = json.loads(p.read_text(encoding="utf-8"))
        check(True, "")
    except Exception as e:
        check(False, f"JSON 파싱 실패 {p.name}: {e}")

readme = (API / "README.md").read_text(encoding="utf-8")
linked = set(re.findall(r"\]\(([\w.-]+\.json)\)", readme))
check(linked == set(docs), f"README 링크 ↔ 파일 불일치: 링크만 {linked - set(docs)}, 파일만 {set(docs) - linked}")

# 목록 표의 번호가 1..N 연속인지
nums = [int(m) for m in re.findall(r"^\| (\d+) \|", readme, re.M)]
check(nums == list(range(1, len(nums) + 1)), f"목록 번호 {nums}")

# 코드값 ↔ DDL CHECK
def ddl_set(col):
    m = re.search(rf"{col}\s+[^\n]*?\n?\s*CHECK \({col} IN \(([^)]*)\)\)", DDL)
    if not m:
        m = re.search(rf"CHECK \({col}\s+IN \(([^)]*)\)\)", DDL)
    return set(re.findall(r"'([A-Z0-9_]+)'", m.group(1)))

def ddl_array(col):
    m = re.search(rf"CHECK \({col} <@ ARRAY\[([^\]]*)\]", DDL)
    return set(re.findall(r"'([A-Z_]+)'", m.group(1)))

codes = docs["codes.json"]
pairs = {"size": "size", "listing": "listing", "ntsStatus": "nts_status", "course": "course", "jobType": "job_type",
         "overtime": "overtime", "stipendBasis": "stipend_basis", "gradeRule": "grade_rule",
         "requirement": "portfolio", "alertKind": "kind", "closeReason": "close_reason", "role": "role"}
for k, col in pairs.items():
    d = ddl_set(col) if col != "kind" else set(re.findall(r"'([A-Z_]+)'", re.search(r"kind\s+varchar\(20\)\s+NOT NULL CHECK \(kind IN \('DOC[^)]*\)\)", DDL).group(0)))
    check(set(codes[k]) == d, f"코드표 {k} ≠ DDL {col}: {set(codes[k]) ^ d}")
check(set(codes["benefit"]) == ddl_array("benefits"), "benefit ≠ DDL")
check(set(codes["weekday"]) == ddl_array("weekdays"), "weekday ≠ DDL")
src_kind = set(re.findall(r"'([A-Z_]+)'", re.search(r"kind\s+varchar\(20\)\s+NOT NULL CHECK \(kind IN \('OPERATION_PLAN[^)]*\)\)", DDL).group(0)))
# 판정 이유 출처(ADR-0023)는 문서 테이블 밖의 원문(참여기관 리스트 칸·모집안내 공지)도 가리켜 sourceType이 더 넓다
check(src_kind <= set(codes["sourceType"]) and set(codes["sourceType"]) - src_kind == {"INSTITUTION_LIST", "SCHOOL_NOTICE"},
      "sourceType ⊇ source_document.kind + INSTITUTION_LIST · SCHOOL_NOTICE")

# 예시 안의 모든 코드값이 코드표에 있는지
FIELD_CODE = {"verdict": "verdict", "layer": "reasonLayer", "result": "reasonResult", "majorMatch": "majorMatch",
              "fit": "fit", "status": "signalStatus", "signalSource": "signalSource", "closeReason": "closeReason",
              "code": "risk", "kind": "alertKind", "size": "size", "listing": "listing", "ntsStatus": "ntsStatus", "course": "course",
              "jobType": "jobType", "overtime": "overtime", "basis": "stipendBasis", "gradeRule": "gradeRule",
              "portfolio": "requirement", "certificate": "requirement", "sourceType": "sourceType",
              "source": "reasonSource", "role": "role",
              "provider": "commuteProvider", "unavailableReason": "commuteUnavailable"}
def walk(o, path, fn):
    if isinstance(o, dict):
        for k, v in o.items():
            fn(k, v, path)
            walk(v, f"{path}.{k}", fn)
    elif isinstance(o, list):
        for i, v in enumerate(o):
            walk(v, f"{path}[{i}]", fn)

def code_check(fname):
    def fn(k, v, path):
        if fname == "codes.json":
            return
        check(re.fullmatch(r"[a-z][A-Za-z0-9]*", k) is not None, f"{fname}{path}.{k}: camelCase 아님")
        if k in FIELD_CODE and isinstance(v, str) and v.isupper() and fname != "certificates.json":
            # 자격증 code는 코드표(codes.json)가 아니라 시드의 자격증 코드표(certificate)에서 온다(ADR-0021)
            check(v in codes[FIELD_CODE[k]], f"{fname}{path}.{k}={v} 코드표에 없음")
        if k in ("weekdays",):
            check(all(x in codes["weekday"] for x in v), f"{fname}{path} 요일 코드")
        if k == "benefits":
            check(all(x in codes["benefit"] for x in v), f"{fname}{path} 복리 코드")
        if k == "name" and "institution" in path:
            check(v.endswith("(가상)"), f"{fname}{path}.name={v} 가상 표시 없음")
        if k == "stipend" and isinstance(v, dict):
            base = 2156880 if v["basis"] == "MONTHLY" else 10320
            check(v["minWageRatio"] == round(v["amount"] / base * 100, 1), f"{fname}{path} 최저임금 대비 % 틀림")
        if k == "signal" and isinstance(v, dict):
            check(v["ratio"] == round(v["interest"] / v["headcount"], 2), f"{fname}{path} ratio 틀림")
            check(0 <= v["liveInterest"] <= v["interest"], f"{fname}{path} 실제 담은 수 > 관심")
            check((v["closesOn"] is None) == (v["closeReason"] is None), f"{fname}{path} 마감일·사유 짝")
    return fn
for name, d in docs.items():
    walk(d, "", code_check(name))

# 신호 status 규칙(asOf 기준)
RECRUIT_END = docs["rounds-current.json"]["recruitEnd"]
for name in ("me-plan-check.json", "center-board.json"):
    d = docs[name]
    check(d["isVirtual"] is True and d["signalSource"] == "REPLAY", f"{name} 가상 표시")
    rows = d.get("items", []) + d.get("alternatives", []) + d.get("rows", [])
    for r in rows:
        s = r["signal"]
        closed = d["asOf"] > RECRUIT_END or (s["closesOn"] is not None and s["closesOn"] <= d["asOf"])
        want = "CLOSED" if closed else "OPEN"  # 몰림 표시는 하지 않는다(ADR-0015)
        check(s["status"] == want, f"{name} job {r['jobId']} status {s['status']} ≠ {want}")
    # 대안: ELIGIBLE · CLOSED 아님 · 남은 자리 > 0 · 담지 않은 직무, why는 규칙 문장(ADR-0016)
    planned = {i["jobId"] for i in docs["me-plan.json"]["items"]}
    first = next((i for i in d.get("items", []) if i["rank"] == 1), None)
    for a in d.get("alternatives", []):
        check(a["remaining"] == a["signal"]["headcount"] - a["signal"]["interest"] and a["remaining"] > 0, f"대안 {a['jobId']} 남은 자리")
        check(a["verdict"] == "ELIGIBLE" and a["signal"]["status"] != "CLOSED", f"대안 {a['jobId']} 조건")
        check(a["jobId"] not in planned, f"대안 {a['jobId']} 이미 담은 직무")
        same = first is not None and first["institution"]["id"] == a["institution"]["id"]
        heads = ["1지망과 같은 기관의 직무이고"] if same else ["관심 분야와 가깝고", "지원 조건을 모두 통과했고"]  # ADR-0022
        tail = "지금 담은 사람이 0명이에요." if a["signal"]["interest"] == 0 else f"남은 자리가 {a['remaining']}개예요."
        check(a["why"] in [f"{h}, {tail}" for h in heads], f"대안 {a['jobId']} why 규칙 문장 아님: {a['why']}")
    r0 = docs["rounds-current.json"]["replay"]
    check(r0["minDate"] <= d["asOf"] <= r0["maxDate"], f"{name} asOf 범위")

# 현황판 요약: 실제 담은 수 합 ≤ 관심 합
check(0 <= docs["center-board.json"]["summary"]["liveInterestTotal"] <= docs["center-board.json"]["summary"]["interestTotal"],
      "현황판 liveInterestTotal > interestTotal")

# 현황판 위험: NARROW_POOL = 적격 풀 200명 미만, DOC_ALERT = 그 직무 또는 그 기관에 검토 알림(ADR-0016)
board = docs["center-board.json"]
for r in board["rows"]:
    codes_in_row = {x["code"] for x in r["risks"]}
    check(("NARROW_POOL" in codes_in_row) == (r["eligiblePool"] < 200), f"현황판 {r['jobId']} NARROW_POOL ↔ eligiblePool {r['eligiblePool']}")
    has_alert = any(a["jobId"] == r["jobId"] or (a["jobId"] is None and a["institution"]["id"] == r["institution"]["id"])
                    for a in board["alerts"])
    check(("DOC_ALERT" in codes_in_row) == has_alert, f"현황판 {r['jobId']} DOC_ALERT ↔ 검토 알림")

# 판정 규칙
for j in docs["eligibility.json"]["jobs"]:
    rs = j["reasons"]
    check(all(r["layer"] == "INSTITUTION" and r["result"] == "CHECK" for r in rs if "alertId" in r),
          f"판정 {j['jobId']} alertId는 판정 항목의 CHECK 행에만")
    check(all("alertId" not in r or r["item"] in ("학년", "학점", "포트폴리오", "자격증") for r in rs if "citation" in r),
          f"판정 {j['jobId']} 알림으로 새로 만든 행에는 citation이 없다(ADR-0023)")
    check(all(r["citation"]["sourceType"] == {"SCHOOL_RULE": "SCHOOL_NOTICE"}.get(r["layer"], r["citation"]["sourceType"])
              and (r["citation"]["page"] is None) == (r["citation"]["sourceType"] != "OPERATION_PLAN")
              for r in rs if "citation" in r), f"판정 {j['jobId']} citation 출처 종류·쪽")
    if any(r["result"] == "NOT_MET" and (r["layer"] == "SCHOOL_RULE" or (r["layer"] == "INSTITUTION" and r["item"] == "자격증"))
           for r in rs):
        want = "INELIGIBLE"  # 학교 규정 미충족 또는 필수 자격증 없음(ADR-0021)
    elif any(r["layer"] == "INSTITUTION" and r["result"] in ("NOT_MET", "CHECK") for r in rs):
        want = "NEEDS_CHECK"
    else:
        want = "ELIGIBLE"
    check(j["verdict"] == want, f"판정 {j['jobId']} {j['verdict']} ≠ {want}")
    check(all(r["result"] == "INFO" for r in rs if r["layer"] == "MAJOR"), f"판정 {j['jobId']} MAJOR는 INFO만")
s = docs["eligibility.json"]["summary"]
check(s["eligible"] + s["needsCheck"] + s["ineligible"] == s["total"], "판정 요약 합계")

# 추천: 5개 이하, INELIGIBLE 없음, 순위 연속, 점수 노출 없음
items = docs["recommendations.json"]["items"]
check(len(items) <= 5 and [i["rank"] for i in items] == list(range(1, len(items) + 1)), "추천 순위")
check(all(i["verdict"] != "INELIGIBLE" for i in items), "추천에 지원 불가 포함")
check(all("score" not in i for i in items), "추천에 점수 노출")

# 지망 순위: 1~3, 중복 없음
ranks = [i["rank"] for i in docs["me-plan.json"]["items"] if i["rank"] is not None]
check(len(ranks) == len(set(ranks)) and all(1 <= r <= 3 for r in ranks), "지망 순위")
req = docs["me-plan-ranks.request.json"]["ranks"]
check(len({r["rank"] for r in req}) == len(req), "순위 요청 중복")

# 프로필 규칙
cert_codes = [c["code"] for c in docs["certificates.json"]["certificates"]]
check(len(set(cert_codes)) == len(cert_codes), "certificates 코드 중복")
for name in ("profile-body.request.json", "me-plan-check.request.json", "me-profile.request.json", "me-profile.json",
             "auth-guest.json"):
    d = docs[name]; certs = (d.get("profile") or d).get("certificates")
    check(certs is None or all(c in cert_codes for c in certs), f"{name} certificates가 자격증 선택지 예시에 없음")
for name in ("profile-body.request.json", "me-plan-check.request.json"):
    p = docs[name]["profile"]
    check(1 <= p["grade"] <= 4 and 0 <= p["completedSemesters"] <= 8 and 0 <= p["gpa"] <= 4.5
          and round(p["gpa"], 1) == p["gpa"], f"{name} 프로필 범위")
check(docs["me-profile.request.json"]["consent"] is True, "프로필 저장 동의")

# 통근: 저장하지 않으므로 상세·추천에 통근 값이 없어야 하고, 조회 응답은 성공·실패 모양이 맞아야 한다
for name, d in docs.items():
    if name == "codes.json" or name.startswith("commute"):
        continue
    walk(d, "", lambda k, v, path, name=name: check(not k.lower().startswith("commute"), f"{name}{path}.{k}: 통근 값은 통근 조회에서만"))
area_codes = [a["code"] for a in docs["areas.json"]["areas"]]
check(all(re.fullmatch(r"(11|28|41)\d{3}", c) for c in area_codes) and len(set(area_codes)) == len(area_codes), "areas 코드 형식·중복")
for name in ("profile-body.request.json", "me-plan-check.request.json", "me-profile.request.json", "commute.request.json"):
    d = docs[name]; hc = (d.get("profile") or d).get("homeAreaCode")
    check(hc is None or hc in area_codes, f"{name} homeAreaCode가 areas 예시에 없음")
for name in ("commute.json", "commute-unavailable.json"):
    c = docs[name]
    check(c["origin"]["type"] in codes["commuteOrigin"], f"{name} origin.type")
    check((c["origin"]["type"] == "SCHOOL") == (c["origin"]["areaCode"] is None), f"{name} 출발지 코드 짝")
    vals = (c["minutes"], c["transfers"], c["fareWon"])
    if c["available"]:
        check(c["unavailableReason"] is None and c["minutes"] > 0 and c["transfers"] >= 0, f"{name} 성공 모양")
    else:
        check(c["unavailableReason"] in codes["commuteUnavailable"] and vals == (None, None, None), f"{name} 실패 모양")
check(docs["commute.json"]["available"] is True and docs["commute-unavailable.json"]["available"] is False, "통근 예시 성공·실패 한 쌍")
check(isinstance(docs["job-detail.json"]["workplace"]["hasCoordinates"], bool), "직무 상세 workplace.hasCoordinates")

# 개인정보: GET 경로·쿼리에 프로필 필드가 없는지(README 목록)
for line in re.findall(r"^\| \d+ \|.*$", readme, re.M):
    cols = [c.strip() for c in line.split("|")]
    if cols[3] == "GET":
        check(not re.search(r"[?&].*(gpa|grade|departmentId|completedSemesters|homeAreaCode)", cols[4]), f"GET 쿼리에 프로필: {cols[4]}")

print(f"검사 {checks}개, 실패 {len(fails)}개")
for f in fails:
    print(" -", f)
sys.exit(1 if fails else 0)
