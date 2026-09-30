"""NCS 능력단위 전체 적재(M3 직무 풀이, P1).

API는 검색 조건 없이 페이지 번호·건수만 받는다 → 전체(2026-09-30 기준 15,520건)를 받아 CSV로 저장한 뒤 매핑에 쓴다.
키: 공공데이터포털 '한국산업인력공단_NCS 관련 정보' 활용신청(자동승인, 개발계정 하루 10,000건) → Decoding 키
사용법: NCS_SERVICE_KEY=<Decoding 키> python ncs_load.py [--rows 1000]
결과: ncs_units.csv
"""
import argparse, csv, os, pathlib, sys, time
import requests

URL = "https://c.q-net.or.kr/openapi/Ncs1info/ncsinfo.do"
FIELDS = ["ncsClCd", "ncsLclasCdnm", "ncsMclasCdnm", "ncsSclasCdnm", "ncsSubdCdnm",
          "compeUnitName", "compeUnitLevel", "compeUnitDef"]
HERE = pathlib.Path(__file__).parent

ap = argparse.ArgumentParser()
ap.add_argument("--rows", type=int, default=1000, help="페이지당 건수. 응답 건수가 이보다 적게 오면 자동으로 맞춘다")
a = ap.parse_args()
key = os.environ.get("NCS_SERVICE_KEY") or sys.exit("환경변수 NCS_SERVICE_KEY(Decoding 키)를 넣으세요.")


def page(no, rows):
    for attempt in range(3):
        try:
            r = requests.get(URL, params={"serviceKey": key, "pageNo": no, "numOfRows": rows}, timeout=60)
            r.raise_for_status()
            root = r.json()["root"]
            return root["info"]["totalCount"], root.get("items", [])
        except Exception as e:  # noqa: BLE001
            print(f"  {no}쪽 재시도 {attempt + 1}: {e}")
            time.sleep(3)
    sys.exit(f"{no}쪽을 받지 못했습니다.")


total, items = page(1, a.rows)
per_page = len(items) or a.rows
out_rows, no = list(items), 1
print(f"전체 {total}건, 페이지당 {per_page}건")
while len(out_rows) < total:
    no += 1
    _, items = page(no, per_page)
    if not items:
        break
    out_rows += items
    print(f"  {len(out_rows)}/{total}")
out = HERE / "ncs_units.csv"
with open(out, "w", encoding="utf-8-sig", newline="") as f:
    w = csv.DictWriter(f, fieldnames=FIELDS, extrasaction="ignore")
    w.writeheader(); w.writerows(out_rows)
subd = {r.get("ncsSubdCdnm") for r in out_rows}
print(f"{len(out_rows)}건 저장({len(subd)}개 세분류) → {out}")
