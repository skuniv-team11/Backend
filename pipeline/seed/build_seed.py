"""시드 만들기: 참여기관 리스트 + 운영계획서 추출(E1) + 수기 추출(E2) + 국세청 상태(E6) + 배정 수 → seed.json (ADR-0014)

    python build_seed.py --list "<참여기관 리스트.xlsx>" --plans <e1 out/full 폴더> --reviews <e2 out 폴더> \\
        --nts <nts_status.csv> --nts-checked-on 2026-10-01 --assigned out/final_assigned.csv \\
        --outcomes ../e2_reviews/out/outcomes.json [--pages out/pages.csv]
    python to_sql.py        # seed.json → R__seed.sql

값을 어디서 가져오나(같은 항목이 두 문서에 있으면 아래 '기준'을 쓰고, 다르면 LIST_MISMATCH 알림을 만든다)
- 참여기관 리스트(센터가 정리·공지한 값) 기준: 정원, 실습지원비, 실습기간, 요일, 근무시간, 학년, 학점, 선호 전공, 근로지 주소, 모집마감 표시
- 운영계획서 추출 기준: 기관 현황, 부서·직무명, 직무 개요·교육목표·요구역량, 주차별 계획, 과정·유형, 연장실습, 근로계약,
  지급 기준, 복리, 자격증, 접수마감일, 근거(쪽·인용문), 문서 내부 불일치
- curated/: 학과(교육통계 2025-10-01), 전공 표기 → 학과 매핑(EXACT·CONFIRMED만 적재), 시드 id, 화면용 고침(overrides),
  자격증 코드표(certificates.csv). 자격증 요건은 사람이 overrides.json에 코드와 함께 적는다(ADR-0021)

넣지 않는 것(ADR-0004): 사업자번호·대표자명·매출액·기타사항, 학과×직무 매칭 집계, 수기의 이름·학과·학년·사진.
"""
import argparse, csv, datetime as dt, glob, itertools, json, pathlib, re, sys

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parent.parent
sys.path.insert(0, str(HERE.parent / "common"))
sys.path.insert(0, str(HERE))
from jobs_sheet import read_jobs, canon  # noqa: E402
import replay  # noqa: E402

CURATED = HERE / "curated"
MIN_WAGE = {"MONTHLY": 2_156_880, "HOURLY": 10_320}       # 2026 최저임금(docs/api README Stipend)
MIN_RATIO = 0.75                                         # 학교 사전교육 자료: 지원비 최저임금 75% 이상
MAX_WEEKLY_HOURS = 40
DAYS = {"월": "MON", "화": "TUE", "수": "WED", "목": "THU", "금": "FRI", "토": "SAT", "일": "SUN"}
SIZE = {"대기업": "LARGE", "중견기업": "MIDSIZE", "중소기업": "SME", "공공기관": "PUBLIC", "협회/기타": "ASSOCIATION_ETC"}
LISTING = {"코스피": "KOSPI", "코스닥": "KOSDAQ", "비상장": "UNLISTED"}
COURSE = {"방학과정": "VACATION", "학기과정": "SEMESTER", "방학/학기 연계과정": "VACATION_SEMESTER"}
JOB_TYPE = {"직무체험형": "EXPERIENCE", "채용연계형": "HIRING"}
OVERTIME = {"없음": "NONE", "상황별 실시": "OCCASIONAL", "주기적/상시적 실시": "REGULAR"}
BASIS = {"월 기준": "MONTHLY", "시간 기준": "HOURLY"}
BENEFITS = {"식사": "MEAL", "교통": "TRANSPORT", "기숙사": "DORM", "현물": "IN_KIND"}
LEVEL = {"필수": "REQUIRED", "우대": "PREFERRED", "언급 없음": "NONE"}
# 자격증·면허를 말하는데 고침(overrides.json certificate)이 없으면 멈춘다. '자격증 : 무관'은 요건이 아니다(ADR-0021)
CERT_MENTION = re.compile(r"자격증|면허")
CERT_NOT_REQUIRED = re.compile(r"자격증\s*:\s*무관")
GRADE = {"3, 4학년": "Y3_4", "4학년": "Y4", "졸업예정자": "GRADUATING"}
GRADE_KO = {"Y3_4": "3·4학년", "Y4": "4학년", "GRADUATING": "졸업예정자"}
NTS = {"01": "ACTIVE", "02": "SUSPENDED", "03": "CLOSED"}
# 추출 레코드 필드(snake) → field_evidence.field_key(camel). V1의 허용 목록과 같아야 한다(아래 check_allowed_keys)
INST_KEYS = {"name": "name", "size": "size", "listing": "listing", "business_type": "businessType",
             "business_item": "businessItem", "address": "address", "application_deadline": "applicationDeadline"}
JOB_KEYS = {"department": "department", "job_title": "jobTitle", "work_address": "workAddress", "course": "course",
            "job_type": "jobType", "period": "period", "hours": "hours", "weekdays": "weekdays",
            "overtime": "overtime", "labor_contract": "laborContract", "stipend_basis": "stipendBasis",
            "stipend_amount": "stipendAmount", "benefits": "benefits", "education_goal": "educationGoal",
            "job_overview": "jobOverview", "major_requirement": "majorRequirement", "headcount": "headcount",
            "grade_requirement": "gradeRequirement", "gpa_requirement": "gpaRequirement",
            "competencies": "competencies", "portfolio": "portfolio", "certificate": "certificate"}
OUTCOME_MAX = 60   # 수기 실습 결과 구절 하나의 최대 글자 수(extract_outcomes.py와 같음)
LIMITS = {("institution", "name"): 100, ("institution", "business_type"): 100, ("institution", "business_item"): 200,
          ("institution", "address"): 200, ("workplace", "address"): 200, ("job", "team"): 100,
          ("job", "title"): 200, ("job", "work_hours_text"): 100, ("job", "certificate_text"): 200,
          ("job", "major_text"): 300, ("job_weekly_plan", "weeks_label"): 30, ("field_evidence", "quote"): 200,
          ("review_alert", "quote_a"): 200, ("review_alert", "quote_b"): 200, ("testimonial", "team_text"): 100,
          ("source_document", "title"): 200, ("department", "name"): 50, ("major_alias", "label"): 100,
          ("department_cluster", "label"): 50,
          ("area", "sido"): 10, ("area", "name"): 20, ("certificate", "label"): 100,
          ("requirement_source", "document_title"): 200, ("requirement_source", "quote"): 300}
SENSITIVE = re.compile(r"\d{3}-?\d{2}-?\d{5}|대표자|대표이사")   # 사업자번호·대표자명이 근거 문구에 섞이면 멈춘다


def fail(msg):
    sys.exit(f"✗ {msg}")


def text(v):
    """화면에 넣을 문자열. 줄바꿈은 \\n 하나로, 앞뒤 공백 제거, 비면 None."""
    v = re.sub(r"\r\n?", "\n", str(v or "")).strip()
    return v or None


def display_name(name):
    """참여기관 리스트 표기에서 법인 형태(주식회사·(주)·㈜)를 뺀다. '(주) 세정' → '세정'."""
    return re.sub(r"\s+", " ", re.sub(r"\(\s*주\s*\)|㈜|주식회사", " ", name)).strip()


def check_allowed_keys():
    ddl = (ROOT / "src/main/resources/db/migration/V1__init.sql").read_text(encoding="utf-8")
    block = ddl[ddl.index("field_evidence_allowed_key"):ddl.index("CREATE UNIQUE INDEX field_evidence_institution_key")]
    inst_part, job_part = block.split("OR")[0], block.split("OR")[1]
    allowed_inst = set(re.findall(r"'(\w+)'", inst_part))
    allowed_job = set(re.findall(r"'(\w+)'", job_part))
    if set(INST_KEYS.values()) != allowed_inst or set(JOB_KEYS.values()) != allowed_job:
        fail("INST_KEYS·JOB_KEYS가 V1 field_evidence 허용 목록과 다릅니다 — 둘을 맞추세요")


# ───────────── 값 정규화(모델은 원문만 준다. 숫자·날짜는 여기서) ─────────────

def won(s):
    d = re.sub(r"[^\d]", "", s or "")
    return int(d) if d else None


def list_lines(cell):
    return [ln.strip() for ln in (cell or "").split("\n") if ln.strip()]


def after_colon(line):
    return line.split(":", 1)[1].strip() if ":" in line else line.lstrip("■ ").strip()


def list_quote(cell, pattern):
    """리스트 칸에서 pattern이 든 줄을 원문 그대로(앞의 ■·- 글머리표만 뗌). 판정 이유 줄의 출처(ADR-0023)."""
    for ln in list_lines(cell):
        if re.search(pattern, ln):
            return re.sub(r"^[■\-*•\s]+", "", ln).strip()
    return None


def list_field(cell, label):
    for ln in list_lines(cell):
        if label in ln:
            return after_colon(ln)
    return None


def list_period(cell, year):
    m = re.search(r"(\d{2,4})\.\s*(\d{1,2})\.\s*(\d{1,2})\.?\s*~\s*(?:(\d{2,4})\.\s*)?(\d{1,2})\.\s*(\d{1,2})", cell)
    if not m:
        return None, None
    y1 = int(m[1]) + (2000 if len(m[1]) == 2 else 0)
    start = dt.date(y1, int(m[2]), int(m[3]))
    y2 = int(m[4]) + (2000 if len(m[4]) == 2 else 0) if m[4] else (y1 if int(m[5]) >= start.month else y1 + 1)
    return start, dt.date(y2, int(m[5]), int(m[6]))


def plan_period(s):
    ds = [dt.date(int(y), int(mo), int(d)) for y, mo, d in re.findall(r"(\d{4})\s*년\s*(\d{1,2})\s*월\s*(\d{1,2})\s*일", s or "")]
    return (ds[0], ds[1]) if len(ds) >= 2 else (None, None)


def list_hours(cell):
    """'■ 근로시간 : 09:00 ~ 18:00' + '※ 금 : 09:00 ~ 17:30' → '09:00 ~ 18:00 (금 09:00 ~ 17:30)'"""
    main = list_field(cell, "근로시간")
    notes = [re.sub(r"\s+:\s+", " ", ln.lstrip("※ ").strip(), count=1) for ln in list_lines(cell) if ln.startswith("※")]
    return (main + (f" ({'; '.join(notes)})" if notes else "")) if main else None


def hhmm_list(s):
    return [f"{int(h):02d}:{m}" for h, m in re.findall(r"(\d{1,2}):(\d{2})", s or "")][:2]


def hhmm_plan(s):
    out = []
    for ampm, h, m in re.findall(r"(오전|오후)?\s*(\d{1,2})\s*시\s*(?:(\d{1,2})\s*분)?", s or ""):
        h = int(h) + (12 if ampm == "오후" and int(h) < 12 else 0)
        out.append(f"{h:02d}:{int(m or 0):02d}")
    return out[:2]


def plan_headcount(s):
    m = re.search(r"(\d+)\s*[명인]", s or "")
    return int(m[1]) if m else None


def plan_gpa(s):
    if not s or any(w in s for w in ("무관", "없음", "상관없")):
        return None
    m = re.search(r"(\d\.\d+)", s)
    return round(float(m[1]), 1) if m else None


def plan_grade(s):
    """확실할 때만. '선호'·'무관'·'무방'이 있으면 None(비교하지 않는다)."""
    if not s or any(w in s for w in ("선호", "무관", "무방")):
        return None
    if "졸업" in s:
        return "GRADUATING"
    nums = set(re.findall(r"[1-4]", s))
    return "Y3_4" if {"3", "4"} <= nums else "Y4" if nums == {"4"} else None


def deadline(month_day_hour, year):
    """접수마감 → 지원 불가가 시작되는 날(closes_on). 그날 안에 시각이 있으면 다음 날부터, '0시'면 그날부터."""
    m, d, h = month_day_hour
    if not int(m) or not int(d):
        return None
    day = dt.date(year, int(m), int(d))
    return day if h != "" and int(h) == 0 else day + dt.timedelta(days=1)


def plan_deadline(s, year):
    m = re.search(r"(\d{1,2})\s*월\s*(\d{1,2})\s*일\s*(?:(\d{1,2})\s*시)?", s or "")
    return deadline((m[1], m[2], m[3] or ""), year) if m else None


def list_deadline(note):
    m = re.search(r"서류마감일\s*:\s*(\d{2,4})\.\s*(\d{1,2})\.\s*(\d{1,2})\.?\s*(?:(\d{1,2}):\d{2})?", note or "")
    if not m:
        return None
    y = int(m[1]) + (2000 if len(m[1]) == 2 else 0)
    return deadline((m[2], m[3], m[4] or ""), y)


# ───────────── 입력 읽기 ─────────────

def load_curated():
    rnd = json.loads((CURATED / "round.json").read_text(encoding="utf-8"))
    deps = list(csv.DictReader((CURATED / "departments.csv").open(encoding="utf-8")))
    aliases = list(csv.DictReader((CURATED / "major_aliases.csv").open(encoding="utf-8")))
    clusters = list(csv.DictReader((CURATED / "department_clusters.csv").open(encoding="utf-8")))
    areas = list(csv.DictReader((CURATED / "areas.csv").open(encoding="utf-8")))
    overrides = json.loads((CURATED / "overrides.json").read_text(encoding="utf-8"))
    certs = list(csv.DictReader((CURATED / "certificates.csv").open(encoding="utf-8")))
    ids_path = CURATED / "ids.json"
    ids = json.loads(ids_path.read_text(encoding="utf-8")) if ids_path.exists() else {}
    return rnd, deps, aliases, clusters, areas, certs, overrides, ids, ids_path


def cluster_rows(clusters, dep_id, live):
    """가까운 학과 묶음(curated/department_clusters.csv) → department_cluster·department_cluster_member 행(ADR-0028).
    CONFIRMED만 넣는다. 학과 이름은 재학생이 있는 학과여야 하고 한 묶음에 둘 이상."""
    rows, members = [], []
    for c in clusters:
        if c["status"] not in ("CONFIRMED", "DRAFT"):
            fail(f"department_clusters.csv {c['label']}: status는 CONFIRMED·DRAFT 중 하나")
        names = [n for n in c["departments"].split(";") if n]
        bad = [n for n in names if n not in live]
        if bad:
            fail(f"department_clusters.csv {c['label']}: 학과 시드에 없는 이름 {bad}")
        if len(set(names)) < 2 or len(set(names)) != len(names):
            fail(f"department_clusters.csv {c['label']}: 서로 다른 학과 둘 이상")
        if c["status"] == "CONFIRMED":
            rows.append({"id": int(c["id"]), "label": c["label"]})
            members += [{"cluster_id": int(c["id"]), "department_id": dep_id[n]} for n in names]
    return rows, members


def certificate_rows(certs):
    """자격증 코드표(curated/certificates.csv) → certificate 행. 순서는 파일 순서(ADR-0021)."""
    rows = [{"code": c["code"], "label": c["label"].strip(), "sort_order": i} for i, c in enumerate(certs, start=1)]
    for r in rows:
        if not re.fullmatch(r"[A-Z][A-Z0-9_]*", r["code"]) or not r["label"]:
            fail(f"certificates.csv {r['code']!r}: 코드는 영문 대문자·숫자·_, 이름은 비울 수 없음")
    if len({r["code"] for r in rows}) != len(rows):
        fail("certificates.csv: 코드가 겹침")
    return rows


def certificate_override(job_key, ov, pj, codes, pages):
    """overrides.json의 자격증 고침 → (요건, 코드, 판정 줄 원문, 근거). 인용은 그 직무 추출 원문 안에 있어야 한다."""
    c = ov["certificate"]
    if c.get("level") not in ("REQUIRED", "PREFERRED") or c.get("code") not in codes:
        fail(f"{job_key}: overrides.json certificate는 level REQUIRED·PREFERRED, code는 certificates.csv에 있는 것")
    if not text(c.get("text")) or not text(c.get("quote")) or not 0 < c.get("page", 0) <= pages:
        fail(f"{job_key}: overrides.json certificate에 text·quote와 문서 안의 page가 필요합니다")
    squash = lambda v: re.sub(r"\s+", "", v or "")  # noqa: E731
    source = "".join(squash(f["value"] if isinstance(f["value"], str) else " ".join(f["value"])) + squash(f["quote"])
                     for f in pj.values() if isinstance(f, dict) and "quote" in f)
    if squash(c["quote"]) not in source:
        fail(f"{job_key}: 자격증 인용이 계획서 추출 원문에 없습니다 — {c['quote'][:40]!r}")
    return c["level"], c["code"], text(c["text"]), {"value": text(c["text"]), "page": c["page"], "quote": c["quote"]}


def area_rows(areas):
    """사는 곳(curated/areas.csv, areas.py가 행정표준코드에서 만듦) → area 행. 순서는 코드 순, 좌표는 없다(ADR-0007)."""
    rows = []
    for i, a in enumerate(sorted(areas, key=lambda r: r["code"]), start=1):
        if not re.fullmatch(r"(11|28|41)\d{3}", a["code"]):
            fail(f"areas.csv {a['code']}: 서울·인천·경기 시·군·구 코드(5자리)가 아님")
        rows.append({"code": a["code"], "sido": a["sido"], "name": a["name"], "sort_order": i})
    if len({(r["sido"], r["name"]) for r in rows}) != len(rows):
        fail("areas.csv: 시·도 + 이름이 겹침")
    return rows


class Ids:
    """시드 id 등록부(curated/ids.json). 한 번 준 id는 바꾸지 않는다 — /jobs/:id와 담아 둔 지망이 같은 행을 가리키게."""
    BASE = {"institution": 1, "workplace": 1, "job": 101}

    def __init__(self, data):
        self.data = {k: dict(data.get(k, {})) for k in self.BASE}
        self.new = []

    def get(self, kind, key):
        table = self.data[kind]
        if key not in table:
            table[key] = max([self.BASE[kind] - 1, *table.values()]) + 1
            self.new.append(f"{kind}:{key}")
        return table[key]


def load_plans(folder, pages):
    plans = {}
    for f in sorted(glob.glob(str(pathlib.Path(folder) / "*.json"))):
        rec = json.loads(pathlib.Path(f).read_text(encoding="utf-8"))
        if rec.get("result") is None or rec.get("stop_reason") == "max_tokens":
            fail(f"{f}: 추출이 비었거나 잘렸습니다 — 다시 추출하세요")
        key = canon(rec["source_file"].split("_")[0])
        n = rec.get("source_pages") or pages.get(rec["source_file"])
        if not n:
            fail(f"{rec['source_file']}: 쪽수를 모릅니다 — --pages CSV에 넣거나 run_extract.py로 다시 추출하세요(source_pages)")
        rec["pages"] = int(n)
        plans[key] = rec
    return plans


def match_jobs(rows, jobs):
    """리스트 직무 행 ↔ 계획서 직무. 이름 유사도 합이 가장 큰 짝(같으면 순서가 가까운 쪽)."""
    def grams(s):
        s = re.sub(r"\s|팀|본부|사업부", "", s)
        return {s[i:i + 2] for i in range(len(s) - 1)} | set(s)

    def sim(a, b):
        A, B = grams(a), grams(b)
        return len(A & B) / max(1, len(A | B))

    def score(r, j):
        name = sim(r["label"], j["department"]["value"] + " " + j["job_title"]["value"])
        body = sim(r["부서직무"], (j["job_overview"]["value"] or "") + " " + j["job_title"]["value"])
        return name + 0.3 * body

    if len(rows) > 8:
        fail(f"{rows[0]['기관']}: 직무가 {len(rows)}개라 짝 맞추기를 바꿔야 합니다")
    best = max(itertools.permutations(range(len(jobs))),
               key=lambda p: sum(score(rows[i], jobs[k]) for i, k in enumerate(p)) - 1e-3 * sum(abs(i - k) for i, k in enumerate(p)))
    return [jobs[k] for k in best]


def load_outcomes(path):
    """extract_outcomes.py 결과 → {(원본 수기 파일, 쪽): [사실 구절]}. 구절은 원문 그대로이고 60자 이내다."""
    data = json.loads(pathlib.Path(path).read_text(encoding="utf-8"))
    out = {}
    for r in data["records"]:
        items = [text(x) for x in r["outcomes"] if text(x)]
        for x in items:
            if len(x) > OUTCOME_MAX:
                fail(f"{r['source']} p{r['text_page']}: 실습 결과 구절 {len(x)}자 > {OUTCOME_MAX}자 — {x[:30]}…")
        out[(r["source"], r["text_page"])] = items
    return out


# ───────────── 만들기 ─────────────

def build(a):
    check_allowed_keys()
    rnd, deps, aliases, clusters, areas, certs, overrides, ids_data, ids_path = load_curated()
    ids = Ids(ids_data)
    round_ = rnd["round"]
    start, end = dt.date.fromisoformat(round_["recruit_start"]), dt.date.fromisoformat(round_["recruit_end"])
    year = start.year
    pages = {}
    if a.pages:
        pages = {r["file"]: int(r["pages"]) for r in csv.DictReader(open(a.pages, encoding="utf-8"))}
    plans = load_plans(a.plans, pages)

    seed = {t: [] for t in ["program", "recruit_round", "department", "area", "certificate", "institution", "workplace",
                            "job", "major_alias", "major_alias_department", "department_cluster", "department_cluster_member",
                            "job_major_alias", "job_weekly_plan",
                            "source_document", "field_evidence", "review_alert", "requirement_source", "testimonial",
                            "replay_signal"]}
    # 판정 이유 줄 출처의 문서명: 리스트 파일 이름에서 끝의 괄호(상시 업데이트 진행중 등)를 뗀다(ADR-0023)
    list_title = re.sub(r"\s*\([^)]*\)\s*$", "", pathlib.Path(a.list).stem).strip()
    seed["program"].append(rnd["program"])
    seed["recruit_round"].append(round_)
    seed["area"] = area_rows(areas)
    seed["certificate"] = certificate_rows(certs)
    cert_codes = {c["code"] for c in seed["certificate"]}
    used_certs = set()

    # 학과: 재학생이 있는 학과만(교육통계 72행 중 폐지·통합 단위는 재학생 0)
    dep_id = {}
    for d in deps:
        dep_id[d["name"]] = int(d["id"])
        if int(d["enrolled_count"]) > 0:
            seed["department"].append({"id": int(d["id"]), "name": d["name"], "college": None,
                                       "enrolled_count": int(d["enrolled_count"]), "enrolled_as_of": d["enrolled_as_of"]})
    live = {d["name"] for d in seed["department"]}

    # 전공 표기: 표기는 전부 넣고, 학과 연결은 사람이 확정한 것(EXACT·CONFIRMED)만
    alias_id = {}
    for al in aliases:
        alias_id[al["label"]] = int(al["id"])
        seed["major_alias"].append({"id": int(al["id"]), "label": al["label"]})
        if al["status"] not in ("EXACT", "CONFIRMED", "DRAFT"):
            fail(f"major_aliases.csv {al['label']}: status는 EXACT·CONFIRMED·DRAFT 중 하나")
        names = [n for n in al["departments"].split(";") if n]
        bad = [n for n in names if n not in live]
        if bad:
            fail(f"major_aliases.csv {al['label']}: 학과 시드에 없는 이름 {bad}")
        if al["status"] == "EXACT" and names != [al["label"]]:
            fail(f"major_aliases.csv {al['label']}: EXACT는 표기와 같은 학과 하나만")
        if al["status"] in ("EXACT", "CONFIRMED"):
            seed["major_alias_department"] += [{"alias_id": int(al["id"]), "department_id": dep_id[n]} for n in names]

    # 가까운 학과 묶음(ADR-0028): 사람이 확정한 것(CONFIRMED)만. 같은 묶음의 학과끼리 '가까운 전공'
    seed["department_cluster"], seed["department_cluster_member"] = cluster_rows(clusters, dep_id, live)

    # 국세청 상태(사업자번호 열은 읽지 않는다)
    nts = {}
    if a.nts:
        if not a.nts_checked_on:
            fail("--nts 를 주면 --nts-checked-on(조회한 날)도 주세요")
        for r in csv.DictReader(open(a.nts, encoding="utf-8-sig")):
            nts[canon(r["기관"])] = NTS.get(r["b_stt_cd"])

    assigned = {}
    for r in csv.DictReader(open(a.assigned, encoding="utf-8")):
        assigned[(canon(r["기관"]), r["직무"].strip())] = int(r["배정"])

    rows = read_jobs(a.list)
    by_inst = {}
    for i, r in enumerate(rows, 1):
        r["seq"] = i
        r["label"] = list_lines(r["부서직무"])[0].lstrip("■ ").strip()
        by_inst.setdefault(canon(r["기관"]), []).append(r)
    missing = set(by_inst) - set(plans)
    if missing or set(plans) - set(by_inst):
        fail(f"리스트와 운영계획서 기관이 다릅니다: 계획서 없음 {sorted(missing)}, 리스트에 없음 {sorted(set(plans) - set(by_inst))}")

    used_assigned = set()
    review = []
    ev_seq = itertools.count(1)
    alert_seq = itertools.count(1)
    jobs_for_replay = []
    job_overrides = overrides.get("job", {})
    used_overrides = set()

    def evidence(doc_id, subject, key, f):
        raw = ", ".join(f["value"]) if isinstance(f["value"], list) else f["value"]
        raw, quote = text(raw), text(f["quote"])
        if not raw or not quote or f["page"] <= 0:
            return
        if SENSITIVE.search(quote) or SENSITIVE.search(raw):
            fail(f"근거 {key}에 사업자번호·대표자로 보이는 문자열 — 이 필드를 빼거나 인용을 줄이세요: {quote[:40]!r}")
        if f["page"] > plan["pages"]:
            fail(f"{plan['source_file']} {key}: 근거 쪽 {f['page']} > 문서 {plan['pages']}쪽")
        row = {"id": next(ev_seq), "source_document_id": doc_id, "institution_id": None, "job_id": None,
               "field_key": key, "raw_value": raw, "page": f["page"], "quote": quote[:200]}
        row[subject[0]] = subject[1]
        seed["field_evidence"].append(row)

    def alert(inst_id, job_id, kind, key, desc, doc_id, f=None, page_b=None, quote_b=None):
        page_a = f["page"] if f and f["page"] > 0 else None
        seed["review_alert"].append({
            "id": next(alert_seq), "institution_id": inst_id, "job_id": job_id, "kind": kind, "field_key": key,
            "description": desc, "source_document_id": doc_id, "page_a": page_a,
            "quote_a": text(f["quote"]) if page_a else None, "page_b": page_b, "quote_b": quote_b})

    doc_seq = itertools.count(1)
    for inst_key in sorted(by_inst, key=lambda k: by_inst[k][0]["seq"]):
        lrows = by_inst[inst_key]
        plan = plans[inst_key]
        res = plan["result"]
        pi = res["institution"]
        if len(res["jobs"]) != len(lrows):
            fail(f"{inst_key}: 리스트 직무 {len(lrows)}개, 계획서 직무 {len(res['jobs'])}개")
        inst_id = ids.get("institution", inst_key)
        name = display_name(lrows[0]["기관"])
        doc_id = next(doc_seq)
        seed["source_document"].append({"id": doc_id, "kind": "OPERATION_PLAN", "title": f"{name} 운영계획서",
                                        "term_code": round_["term_code"], "institution_id": inst_id,
                                        "page_count": plan["pages"]})
        status = nts.get(inst_key)
        seed["institution"].append({
            "id": inst_id, "name": name, "size": SIZE.get(pi["size"]["value"], "UNSPECIFIED"),
            "listing": LISTING.get(pi["listing"]["value"], "UNSPECIFIED"),
            "business_type": text(pi["business_type"]["value"]), "business_item": text(pi["business_item"]["value"]),
            "address": text(pi["address"]["value"]), "nts_status": status,
            "nts_checked_on": a.nts_checked_on if status else None})
        for k, key in INST_KEYS.items():
            evidence(doc_id, ("institution_id", inst_id), key, pi[k])
        for x in res["inconsistencies"]:
            alert(inst_id, None, "DOC_INCONSISTENCY", None, text(x["description"]), doc_id,
                  {"page": x["page_a"], "quote": x["quote_a"]},
                  x["page_b"] if x["page_b"] > 0 else None, text(x["quote_b"]) if x["page_b"] > 0 else None)
        plan_close = plan_deadline(pi["application_deadline"]["value"], year)

        for r, pj in zip(lrows, match_jobs(lrows, res["jobs"])):
            g = lambda k: pj[k]["value"]  # noqa: E731
            job_key = f"{inst_key}|{r['label']}"
            job_id = ids.get("job", job_key)
            address = after_colon(list_lines(r["근로지주소"])[0]) if r["근로지주소"] else text(g("work_address"))
            wp_id = None
            if address:
                wp_id = ids.get("workplace", f"{inst_key}|{address}")
                if all(w["id"] != wp_id for w in seed["workplace"]):
                    # 좌표는 넣지 않는다 — 통근 조회 때 카카오 주소 검색으로 구하고 버린다(ADR-0007)
                    seed["workplace"].append({"id": wp_id, "institution_id": inst_id, "address": address})
            # 리스트 값
            l_start, l_end = list_period(r["기간요일시간"], year)
            l_days = [DAYS[d] for d in re.findall(r"[월화수목금토일]", list_field(r["기간요일시간"], "근로요일") or "")]
            l_major = list_field(r["선호전공학년"], "전공")
            l_grade = list_field(r["선호전공학년"], "학년")
            if l_grade not in GRADE:
                fail(f"{job_key}: 리스트 학년 '{l_grade}'를 모릅니다 — GRADE에 추가하세요")
            note = r["비고"]
            m = re.search(r"학점\s*(\d(?:\.\d+)?)\s*이상", note)
            l_gpa = float(m[1]) if m else None
            l_portfolio = "REQUIRED" if re.search(r"포트폴리오[^\n]*필수", note) else None
            l_hours = list_hours(r["기간요일시간"])
            headcount = int(float(r["모집인원"]))
            stipend = won(r["실습지원비"])
            # 계획서 값
            basis = BASIS.get(g("stipend_basis"), "UNSPECIFIED")
            p_portfolio = LEVEL[g("portfolio")]
            # 선호 전공: 리스트 표기 그대로 두되, 계획서가 '전공 무관'이면 전공 무관으로 본다 — 기관이 직접 쓴 문서라서
            # (10/7 사용자 결정, ADR-0025). 리스트와 다르면 아래에서 LIST_MISMATCH 알림도 그대로 만든다
            major_open = "무관" in (g("major_requirement") or "")
            portfolio = l_portfolio or p_portfolio
            certificate = LEVEL[g("certificate")]
            certificate_code = certificate_text = None
            benefits = [BENEFITS[b] for b in g("benefits")]
            if "식사" in note and "MEAL" not in benefits:
                benefits.append("MEAL")
            benefits = [b for b in BENEFITS.values() if b in benefits]
            weekly = re.search(r"\d+(?:\.\d+)?", pi["weekly_hours"]["value"] or "")
            weekly_hours = float(weekly[0]) if weekly else None
            # 마감: 계획서 접수마감과 센터 모집마감 중 이른 날. 회차 종료일보다 이를 때만(ADR-0009)
            closes = []
            if plan_close and plan_close <= end:
                closes.append((plan_close, "APPLICATION_DEADLINE"))
            center_closed = "모집마감" in note
            l_close = list_deadline(note) if center_closed else None
            if l_close and l_close <= end:
                closes.append((l_close, "CENTER_CLOSED"))
            closes.sort()
            closes_on, close_reason = closes[0] if closes else (None, None)
            center_undated = center_closed and not l_close
            if center_undated and not closes_on:
                close_reason = "CENTER_CLOSED"
            ov = job_overrides.get(job_key, {})
            if ov:
                used_overrides.add(job_key)
            # 자격증: 판정이 코드로 보므로(필수인데 없으면 지원 불가) 사람이 코드를 적은 고침만 쓴다(ADR-0021)
            if "certificate" in ov:
                certificate, certificate_code, certificate_text, pj["certificate"] = certificate_override(
                    job_key, ov, pj, cert_codes, plan["pages"])
                used_certs.add(certificate_code)
            elif certificate != "NONE":
                fail(f"{job_key}: 계획서 자격증이 {g('certificate')}인데 코드가 없습니다 — "
                     f"curated/certificates.csv와 overrides.json certificate에 넣으세요")
            else:
                said = " ".join(text(g(k)) or "" for k in ("competencies", "major_requirement", "certificate"))
                if CERT_MENTION.search(CERT_NOT_REQUIRED.sub("", said)):
                    fail(f"{job_key}: 계획서가 자격증·면허를 말하는데 자격증 칸은 '언급 없음'입니다 — 원문을 보고 "
                         f"overrides.json certificate(필수·우대와 코드)를 넣으세요: {said[:60]!r}")
            title = g("job_title")
            m = re.match(r"^\[\s*(.+?)\(\d+\)\s*-\s*(.+?)\s*\]$", title or "")   # 세정: '[디지털미디어팀(1)- SNS, 영상]'
            title = m[2] if m else title
            team = ov.get("team") or text(g("department"))
            title = ov.get("title") or text(title)
            fa = assigned.get((inst_key, r["label"]), 0)
            if (inst_key, r["label"]) in assigned:
                used_assigned.add((inst_key, r["label"]))
            job = {
                "id": job_id, "round_id": round_["id"], "institution_id": inst_id, "workplace_id": wp_id,
                "list_seq": r["seq"], "team": team, "title": title, "overview": text(g("job_overview")),
                "education_goal": text(g("education_goal")),
                "competencies": text(g("competencies")) or text(r["요구역량"]),
                "course": COURSE.get(g("course"), "UNSPECIFIED"), "job_type": JOB_TYPE.get(g("job_type"), "UNSPECIFIED"),
                "period_start": l_start.isoformat() if l_start else None,
                "period_end": l_end.isoformat() if l_end else None,
                "work_hours_text": l_hours, "weekly_hours": weekly_hours, "weekdays": l_days,
                "overtime": OVERTIME.get(g("overtime"), "UNSPECIFIED"),
                "labor_contract": {"Y": True, "N": False}.get(g("labor_contract")),
                "stipend_basis": basis, "stipend_amount": stipend, "benefits": benefits,
                "headcount": headcount, "grade_rule": GRADE[l_grade], "gpa_min": l_gpa,
                "portfolio": portfolio, "certificate": certificate, "certificate_code": certificate_code,
                "certificate_text": certificate_text,
                "major_text": l_major, "major_open": major_open,
                "closes_on": closes_on.isoformat() if closes_on else None, "close_reason": close_reason,
                "closes_on_is_virtual": False, "final_assigned": fa}
            if not team or not title:
                fail(f"{job_key}: 부서나 직무명이 비었습니다 — curated/overrides.json에 넣으세요")
            seed["job"].append(job)
            jobs_for_replay.append({"id": job_id, "headcount": headcount, "final_assigned": fa,
                                    "closes_on": closes_on, "center_closed_undated": center_undated and not closes_on})
            for lab in re.split(r",\s*", l_major or ""):
                if lab:
                    if lab not in alias_id:
                        fail(f"{job_key}: 전공 표기 '{lab}'가 curated/major_aliases.csv에 없습니다")
                    seed["job_major_alias"].append({"job_id": job_id, "alias_id": alias_id[lab]})
            for i, w in enumerate(pj["weekly_plan"], 1):
                if text(w["content"]):
                    seed["job_weekly_plan"].append({"job_id": job_id, "seq": i, "weeks_label": text(w["weeks"]) or f"{i}",
                                                    "content": text(w["content"])})
            for k, key in JOB_KEYS.items():
                evidence(doc_id, ("job_id", job_id), key, pj[k])

            # 판정 이유 줄의 출처(ADR-0023): 판정이 쓴 값이 나온 원문. 리스트 값은 리스트 칸, 계획서 값은 계획서 쪽·인용
            def source(item, quote, page=None, title=list_title, kind="INSTITUTION_LIST"):
                if not quote:
                    fail(f"{job_key}: 판정 이유 출처({item})의 원문을 못 찾았습니다")
                quote = re.sub(r"^[■\-*•\s]+", "", quote).strip()   # 앞의 글머리표만 뗀다(추천 인용과 같음)
                seed["requirement_source"].append({"job_id": job_id, "item": item, "source_type": kind,
                                                   "document_title": title, "page": page, "quote": quote[:300]})

            source("GRADE", list_quote(r["선호전공학년"], r"학년"))
            if major_open:   # 판정이 쓴 값('전공 무관')은 계획서 쪽·인용(ADR-0025)
                f = pj["major_requirement"]
                source("MAJOR", text(f["quote"]), f["page"], f"{name} 운영계획서", "OPERATION_PLAN")
            elif l_major:
                source("MAJOR", list_quote(r["선호전공학년"], r"전공"))
            if l_gpa:
                source("GPA", list_quote(note, r"학점\s*\d"))
            plan_title = f"{name} 운영계획서"
            if l_portfolio:
                source("PORTFOLIO", list_quote(note, r"포트폴리오[^\n]*필수"))
            elif portfolio != "NONE" and pj["portfolio"]["page"] > 0 and text(pj["portfolio"]["quote"]):
                f = pj["portfolio"]   # 계획서에만 있는 포트폴리오 요건(2026-2는 없음). 근거 쪽이 없으면 출처 없이 둔다
                source("PORTFOLIO", text(f["quote"]), f["page"], plan_title, "OPERATION_PLAN")
            if certificate != "NONE":
                f = pj["certificate"]
                source("CERTIFICATE", text(f["quote"]), f["page"], plan_title, "OPERATION_PLAN")

            # 리스트 ↔ 계획서 비교(LIST_MISMATCH). 계획서가 확실할 때만 비교한다
            def mismatch(key, field, what, l_val, p_val):
                alert(inst_id, job_id, "LIST_MISMATCH", key,
                      f"{what}: 참여기관 리스트는 {l_val}, 운영계획서는 {p_val}", doc_id, pj[field])

            p_hc = plan_headcount(g("headcount"))
            if p_hc is not None and p_hc != headcount:
                mismatch("headcount", "headcount", "모집 인원", f"{headcount}명", f"{p_hc}명")
            p_st = won(g("stipend_amount"))
            if p_st and p_st != stipend:
                mismatch("stipendAmount", "stipend_amount", "실습지원비", f"{stipend:,}원", f"{p_st:,}원")
            p_start, p_end = plan_period(g("period"))
            if p_start and (p_start, p_end) != (l_start, l_end):
                mismatch("period", "period", "실습기간", f"{l_start}~{l_end}", f"{p_start}~{p_end}")
            p_days = [DAYS[d] for d in g("weekdays")]
            if p_days and set(p_days) != set(l_days):
                mismatch("weekdays", "weekdays", "실습 요일", ", ".join(l_days), ", ".join(p_days))
            l_hm, p_hm = hhmm_list(l_hours), hhmm_plan(g("hours"))
            if len(p_hm) == 2 and len(l_hm) == 2 and p_hm != l_hm:
                mismatch("hours", "hours", "실습 시간", "~".join(l_hm), "~".join(p_hm))
            p_grade = plan_grade(g("grade_requirement"))
            if p_grade and p_grade != GRADE[l_grade]:
                mismatch("gradeRequirement", "grade_requirement", "학년", GRADE_KO[GRADE[l_grade]], GRADE_KO[p_grade])
            p_gpa = plan_gpa(g("gpa_requirement"))
            if p_gpa != l_gpa and (p_gpa or l_gpa):
                mismatch("gpaRequirement", "gpa_requirement", "학점",
                         f"{l_gpa} 이상" if l_gpa else "조건 없음", f"{p_gpa} 이상" if p_gpa else "조건 없음")
            if "무관" in (g("major_requirement") or "") and l_major:
                mismatch("majorRequirement", "major_requirement", "선호 전공", l_major, "전공 무관")
            if l_portfolio and p_portfolio != l_portfolio:
                mismatch("portfolio", "portfolio", "포트폴리오", "필수", {"PREFERRED": "우대", "NONE": "언급 없음"}[p_portfolio])
            # 규정 점검(RULE_CHECK)
            if stipend and basis in MIN_WAGE and stipend < MIN_WAGE[basis] * MIN_RATIO:
                alert(inst_id, job_id, "RULE_CHECK", "stipendAmount",
                      f"실습지원비 {stipend:,}원이 최저임금의 {MIN_RATIO:.0%}({MIN_WAGE[basis] * MIN_RATIO:,.0f}원)보다 적습니다", doc_id,
                      pj["stipend_amount"])
            if weekly_hours and weekly_hours > MAX_WEEKLY_HOURS:
                alert(inst_id, job_id, "RULE_CHECK", "hours", f"주 {weekly_hours:g}시간 — 주 {MAX_WEEKLY_HOURS}시간을 넘습니다", doc_id,
                      pi["weekly_hours"])
            review.append({"job_id": job_id, "list_seq": r["seq"], "기관": name, "team": team, "title": title,
                           "plan_department": g("department"), "plan_title": g("job_title"),
                           "headcount": headcount, "final_assigned": fa, "stipend": stipend, "grade": l_grade,
                           "gpa_min": l_gpa, "portfolio": portfolio, "certificate": certificate,
                           "certificate_code": certificate_code, "major_text": l_major,
                           "closes_on": job["closes_on"], "close_reason": close_reason})

    unused = set(assigned) - used_assigned
    if unused:
        fail(f"배정 수를 붙일 직무를 못 찾음: {sorted(unused)} — 매칭 결과의 직무 표기와 리스트 첫 줄을 맞추세요")
    if set(job_overrides) - used_overrides:
        fail(f"overrides.json에 쓰이지 않은 키: {sorted(set(job_overrides) - used_overrides)}")
    if cert_codes - used_certs:
        fail(f"certificates.csv에 어느 직무도 쓰지 않는 코드: {sorted(cert_codes - used_certs)} — 선택지에서 빼세요(최소 수집)")

    # 수기(E2): 2026-2 참여기관에 연결되는 것만. 이름·학과·학년·사진·소감은 넣지 않는다.
    # 실습 결과는 extract_outcomes.py가 원문에서 고른 사실 구절만 넣는다(감상·배운 점·개인 진로는 버림, ADR-0020)
    outcomes = load_outcomes(a.outcomes)
    inst_ids = ids.data["institution"]
    by_term = {}
    for f in sorted(glob.glob(str(pathlib.Path(a.reviews) / "*.json"))):
        rec = json.loads(pathlib.Path(f).read_text(encoding="utf-8"))
        src = re.sub(r"_(part\d+|small)(?=\.pdf$)", "", rec["source_file"])
        for t in rec["result"]["records"]:
            term = re.match(r"(\d{4}-[12])", t["semester"])[1]
            by_term.setdefault(term, {"src": src, "records": []})["records"].append(t)
    t_seq = itertools.count(1)
    for term in sorted(by_term):
        src = by_term[term]["src"]
        linked = [t for t in by_term[term]["records"] if canon(t["institution"], t["department"]) in inst_ids]
        if not linked:
            continue
        if src not in pages:
            fail(f"{src}: 쪽수를 모릅니다 — --pages CSV에 넣으세요")
        doc_id = next(doc_seq)
        seed["source_document"].append({"id": doc_id, "kind": "TESTIMONIAL", "title": f"{term} 우수 참여수기",
                                        "term_code": term, "institution_id": None, "page_count": pages[src]})
        for t in sorted(linked, key=lambda t: t["text_page"]):
            acts = [x for x in (text(v) for v in t["activities"]) if x]
            if not acts or not 0 < t["text_page"] <= pages[src]:
                fail(f"{term} {t['institution']}: 실습 내용이 비었거나 쪽 번호가 문서 밖")
            if (src, t["text_page"]) not in outcomes:
                fail(f"{term} {t['institution']} p{t['text_page']}: 실습 결과 구절이 없습니다 — "
                     f"e2_reviews/extract_outcomes.py를 다시 돌리세요")
            seed["testimonial"].append({"id": next(t_seq), "source_document_id": doc_id,
                                        "institution_id": inst_ids[canon(t["institution"], t["department"])],
                                        "team_text": text(t["department"]), "activities": acts,
                                        "outcomes": outcomes[(src, t["text_page"])], "page": t["text_page"]})

    # 리플레이(가상 신호)
    signals, virtual = replay.generate(jobs_for_replay, start, end, rnd["replay_seed"])
    for j in seed["job"]:
        if j["id"] in virtual:
            j["closes_on"], j["closes_on_is_virtual"] = virtual[j["id"]].isoformat(), True
    seed["replay_signal"] = signals

    validate(seed)
    return seed, review, ids, ids_path


def validate(seed):
    for (t, c), n in LIMITS.items():
        for r in seed[t]:
            if r[c] is not None and len(r[c]) > n:
                fail(f"{t}.{c} {len(r[c])}자 > {n}자: {r[c][:50]!r}")
    for t in ("institution", "workplace", "job", "source_document", "field_evidence", "review_alert", "testimonial"):
        idv = [r["id"] for r in seed[t]]
        if len(idv) != len(set(idv)):
            fail(f"{t}: id 중복")
    names = [r["name"] for r in seed["institution"]]
    if len(names) != len(set(names)):
        fail("institution.name 중복")
    for j in seed["job"]:
        total = sum(s["interest_count"] for s in seed["replay_signal"] if s["job_id"] == j["id"])
        if total != j["final_assigned"]:
            fail(f"job {j['id']}: 리플레이 관심 합 {total} ≠ 배정 {j['final_assigned']}")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--list", required=True, help="참여기관 리스트 xlsx")
    ap.add_argument("--plans", required=True, help="운영계획서 본 추출 결과 폴더(e1 out/full)")
    ap.add_argument("--reviews", required=True, help="수기 추출 결과 폴더(e2 out)")
    ap.add_argument("--assigned", required=True, help="matching_counts.py 결과 CSV")
    ap.add_argument("--outcomes", required=True, help="수기 실습 결과 사실 구절(e2 extract_outcomes.py 결과 JSON)")
    ap.add_argument("--nts", help="e6 nts_status.csv")
    ap.add_argument("--nts-checked-on", help="국세청 조회한 날(YYYY-MM-DD)")
    ap.add_argument("--pages", help="원본 PDF 쪽수 CSV(file,pages). 추출 JSON에 source_pages가 없을 때")
    ap.add_argument("--out", default=str(HERE / "seed.json"))
    ap.add_argument("--review", default=str(HERE / "out" / "review_seed.csv"))
    a = ap.parse_args()
    seed, review, ids, ids_path = build(a)

    pathlib.Path(a.out).write_text(json.dumps(seed, ensure_ascii=False, indent=1) + "\n", encoding="utf-8", newline="\n")
    if ids.new:
        ids_path.write_text(json.dumps(ids.data, ensure_ascii=False, indent=1) + "\n", encoding="utf-8", newline="\n")
        print(f"새 id {len(ids.new)}개를 curated/ids.json에 적었습니다(같이 커밋)")
    rp = pathlib.Path(a.review)
    rp.parent.mkdir(parents=True, exist_ok=True)
    with rp.open("w", encoding="utf-8-sig", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(review[0]))
        w.writeheader()
        w.writerows(review)
    c = {t: len(v) for t, v in seed.items()}
    draft = sum(1 for al in csv.DictReader((CURATED / "major_aliases.csv").open(encoding="utf-8")) if al["status"] == "DRAFT")
    print(f"기관 {c['institution']} · 직무 {c['job']} · 정원 {sum(j['headcount'] for j in seed['job'])} · "
          f"배정 {sum(j['final_assigned'] for j in seed['job'])} · 학과 {c['department']} · 전공 표기 {c['major_alias']}"
          f"(학과 연결 {len({m['alias_id'] for m in seed['major_alias_department']})}, 확정 대기 {draft}) · 근거 {c['field_evidence']} · "
          f"알림 {c['review_alert']} · 수기 {c['testimonial']}(실습 결과 구절 {sum(len(t['outcomes']) for t in seed['testimonial'])}) · "
          f"신호 {c['replay_signal']}행")
    print(f"→ {a.out}\n→ {rp} (사람 검토용, 커밋하지 않음)\n다음: python to_sql.py")


if __name__ == "__main__":
    main()
