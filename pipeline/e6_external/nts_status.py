"""국세청 사업자등록 상태조회: 참여기관 18곳이 계속사업자인지 확인한다(M2 휴·폐업 탐지).

키: 공공데이터포털 '국세청_사업자등록정보 진위확인 및 상태조회 서비스' 활용신청(자동승인)
    → 마이페이지의 **일반 인증키(Decoding)** 를 쓴다. Encoding 키를 쓰면 한 번 더 인코딩돼 인증이 실패한다.
사용법: NTS_SERVICE_KEY=<Decoding 키> python nts_status.py [--csv ../e1_operation_plan/data/answer_institutions.csv]
결과: nts_status.csv
"""
import argparse, csv, os, pathlib, re, sys
import requests

URL = "https://api.odcloud.kr/api/nts-businessman/v1/status"
HERE = pathlib.Path(__file__).parent

ap = argparse.ArgumentParser()
ap.add_argument("--csv", default=str(HERE.parent / "e1_operation_plan" / "data" / "answer_institutions.csv"))
ap.add_argument("--column", default="사업자등록번호(계획서)")
a = ap.parse_args()

key = os.environ.get("NTS_SERVICE_KEY") or sys.exit("환경변수 NTS_SERVICE_KEY(Decoding 키)를 넣으세요.")
rows = list(csv.DictReader(open(a.csv, encoding="utf-8-sig")))
nos = {re.sub(r"\D", "", r[a.column]): r.get("기관", "") for r in rows if re.sub(r"\D", "", r[a.column])}
if not nos:
    sys.exit("사업자번호를 찾지 못했습니다.")

# requests가 serviceKey를 한 번만 URL 인코딩한다(Decoding 키 + params 사용이 핵심)
resp = requests.post(URL, params={"serviceKey": key}, json={"b_no": list(nos)[:100]}, timeout=30)
if resp.status_code != 200:
    sys.exit(f"HTTP {resp.status_code}: {resp.text[:300]}\n→ 401/-4/-5면 키 종류(Decoding)·활용신청 승인·발급 직후 반영 지연(최대 1~2시간)을 확인하세요.")
data = resp.json().get("data", [])
out = HERE / "nts_status.csv"
fields = ["기관", "b_no", "b_stt", "b_stt_cd", "tax_type", "end_dt"]
with open(out, "w", encoding="utf-8-sig", newline="") as f:
    w = csv.DictWriter(f, fieldnames=fields, extrasaction="ignore")
    w.writeheader()
    for d in data:
        w.writerow({"기관": nos.get(d.get("b_no", ""), ""), **d})
bad = [d for d in data if d.get("b_stt_cd") != "01"]
print(f"{len(data)}곳 조회, 계속사업자 아님 {len(bad)}곳 → {out}")
for d in bad:
    print(f"  - {nos.get(d.get('b_no'), '?')} {d.get('b_no')}: {d.get('b_stt') or d.get('tax_type')}")
