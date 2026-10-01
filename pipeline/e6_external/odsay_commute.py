"""(보관용) 서경대 → 근로지 대중교통 소요시간을 ODsay로 부른다.

10/1부터 결과를 시드에 쓰지 않는다: ODsay 운영정책(제4조 5항 10호)이 사전 동의 없는 결과 저장·가공을 금지한다.
통근은 서비스가 카카오 대중교통 API를 실시간으로 부른다(docs/decisions/0007-commute-kakao-live.md).
이 스크립트는 API 호출 확인과 근로지 목록(template) 만들기에만 쓴다.

실행 중(Render)에는 부르지 않는다: ODsay 서버 키는 IP를 최대 5개만 등록할 수 있고 Render 아웃바운드 IP는 공유 대역이라,
본인 PC의 공인 IP로 서버 키를 받아 로컬에서 한 번 계산하고 결과만 시드로 쓴다.

1단계(주소 목록 만들기):
  python odsay_commute.py template --jobs-xlsx "<참여기관 리스트.xlsx>"
  → workplaces.csv 의 lat, lng 칸을 채운다(카카오맵·네이버지도에서 주소 검색 → 좌표 복사)
2단계(계산):
  ODSAY_API_KEY=<서버 키> python odsay_commute.py run --origin-lat <서경대 위도> --origin-lng <서경대 경도>
  → commute.csv. 무료는 하루 30건이라 기본 30건에서 멈추고, 이미 받은 곳은 건너뛴다(캐시).
"""
import argparse, csv, json, os, pathlib, re, sys, time
import requests

HERE = pathlib.Path(__file__).parent
sys.path.insert(0, str(HERE.parent / "common"))
URL = "https://api.odsay.com/v1/api/searchPubTransPathT"
CACHE = HERE / "odsay_cache"


def template(a):
    from jobs_sheet import read_jobs
    seen, rows = set(), []
    for j in read_jobs(a.jobs_xlsx):
        addr = re.sub(r"^[■\s]+", "", j["근로지주소"]).split("\n")[0].strip()
        k = (j["기관"], addr)
        if addr and k not in seen:
            seen.add(k); rows.append({"기관": j["기관"], "주소": addr, "lat": "", "lng": ""})
    out = HERE / "workplaces.csv"
    with open(out, "w", encoding="utf-8-sig", newline="") as f:
        w = csv.DictWriter(f, fieldnames=["기관", "주소", "lat", "lng"]); w.writeheader(); w.writerows(rows)
    print(f"{len(rows)}곳 → {out}. lat/lng 를 채운 뒤 run 을 실행하세요.")


def best_path(body):
    if "error" in body:
        err = body["error"]
        err = err[0] if isinstance(err, list) else err
        return None, f"{err.get('code')} {err.get('msg') or err.get('message')}"
    paths = body.get("result", {}).get("path", [])
    if not paths:
        return None, "경로 없음"
    info = min((p["info"] for p in paths), key=lambda i: i.get("totalTime", 10 ** 9))
    return info, ""


def run(a):
    key = os.environ.get("ODSAY_API_KEY") or sys.exit("환경변수 ODSAY_API_KEY(서버 키)를 넣으세요.")
    CACHE.mkdir(exist_ok=True)
    rows = list(csv.DictReader(open(HERE / "workplaces.csv", encoding="utf-8-sig")))
    calls, out = 0, []
    for r in rows:
        if not (r["lat"] and r["lng"]):
            out.append({**r, "소요분": "", "환승": "", "도보m": "", "요금": "", "메모": "좌표 없음"}); continue
        cf = CACHE / (re.sub(r"[^\w가-힣]", "_", f"{r['기관']}_{r['lat']}_{r['lng']}") + ".json")
        if cf.exists():
            body = json.loads(cf.read_text(encoding="utf-8"))
        else:
            if calls >= a.limit:
                out.append({**r, "소요분": "", "환승": "", "도보m": "", "요금": "", "메모": "오늘 한도 도달 — 내일 다시 실행"}); continue
            resp = requests.get(URL, params={"SX": a.origin_lng, "SY": a.origin_lat, "EX": r["lng"], "EY": r["lat"],
                                             "apiKey": key}, timeout=30)   # requests가 apiKey 특수문자를 인코딩한다
            body = resp.json(); calls += 1; time.sleep(0.5)
            if "error" not in body:
                cf.write_text(json.dumps(body, ensure_ascii=False), encoding="utf-8")
        info, msg = best_path(body)
        out.append({**r, "소요분": info and info.get("totalTime"),
                    "환승": info and max(0, info.get("busTransitCount", 0) + info.get("subwayTransitCount", 0) - 1),
                    "도보m": info and info.get("totalWalk"), "요금": info and info.get("payment"), "메모": msg})
    dst = HERE / "commute.csv"
    with open(dst, "w", encoding="utf-8-sig", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(out[0])); w.writeheader(); w.writerows(out)
    done = sum(1 for o in out if o["소요분"] not in ("", None))
    print(f"API 호출 {calls}건, 계산 완료 {done}/{len(out)}곳 → {dst}")


ap = argparse.ArgumentParser()
sub = ap.add_subparsers(dest="cmd", required=True)
t = sub.add_parser("template"); t.add_argument("--jobs-xlsx", required=True)
r = sub.add_parser("run")
r.add_argument("--origin-lat", required=True); r.add_argument("--origin-lng", required=True)
r.add_argument("--limit", type=int, default=30, help="하루 무료 한도")
a = ap.parse_args()
template(a) if a.cmd == "template" else run(a)
