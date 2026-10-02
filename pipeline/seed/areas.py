"""행정표준코드 법정동코드 전체자료 → curated/areas.csv (사는 곳 선택지, ADR-0007).

    python areas.py "<법정동코드 전체자료.zip 또는 .txt>"

원본: 행정표준코드관리시스템(code.go.kr) → 코드검색 → 법정동코드 목록조회 → [법정동 코드 전체자료].
원본 파일은 저장소에 넣지 않는다. 이 스크립트가 만든 areas.csv(코드·시도·이름)만 커밋한다.

고르는 규칙
- 서울(11)·인천(28)·경기(41)의 시·군·구 단위(10자리 중 뒤 5자리가 00000, 시·도 행 제외)이고 폐지여부가 '존재'인 행
- 일반구가 있는 시(예: 수원시 41110 → 장안구 41111 …)는 시 행을 빼고 구만 남긴다. 사는 곳은 가장 작은 시·군·구로 고른다
- 이름은 시·도를 뗀 나머지(예: '경기도 수원시 장안구' → '수원시 장안구'), 시·도는 '서울'·'인천'·'경기'
- 순서는 코드 순(area.sort_order)
좌표는 넣지 않는다 — 통근 조회 때 카카오 주소 검색으로 그때 구한다.
"""
import argparse, csv, io, pathlib, re, sys, zipfile

HERE = pathlib.Path(__file__).resolve().parent
OUT = HERE / "curated" / "areas.csv"
SIDO = {"11": "서울", "28": "인천", "41": "경기"}


def read_text(path):
    p = pathlib.Path(path)
    data = p.read_bytes()
    if p.suffix.lower() == ".zip":
        with zipfile.ZipFile(io.BytesIO(data)) as z:
            txts = [i for i in z.infolist() if i.filename.lower().endswith(".txt")]
            if len(txts) != 1:
                sys.exit(f"zip 안에 txt가 하나여야 합니다: {[i.filename for i in z.infolist()]}")
            data = z.read(txts[0])
    for enc in ("cp949", "utf-8-sig"):
        try:
            return data.decode(enc)
        except UnicodeDecodeError:
            pass
    sys.exit("인코딩을 읽을 수 없습니다(cp949·utf-8 아님)")


def select(text):
    """(code5, sido, name) 목록. 코드 순."""
    lines = text.splitlines()
    if not lines or lines[0].split("\t")[:3] != ["법정동코드", "법정동명", "폐지여부"]:
        sys.exit(f"머리글이 다릅니다: {lines[:1]!r} — '법정동코드\\t법정동명\\t폐지여부' 형식이어야 합니다")
    live = {}
    for line in lines[1:]:
        cols = line.split("\t")
        if len(cols) < 3:
            continue
        code, name, status = cols[0].strip(), re.sub(r"\s+", " ", cols[1]).strip(), cols[2].strip()
        if code[:2] in SIDO and code[5:] == "00000" and code[2:5] != "000" and status == "존재":
            live[code[:5]] = name
    rows = []
    for code, full in sorted(live.items()):
        # 일반구를 둔 시: 같은 앞 4자리에 끝자리가 0이 아닌 구가 있으면 시 행은 뺀다
        if code[4] == "0" and any(c[:4] == code[:4] and c[4] != "0" for c in live):
            continue
        sido_full, _, rest = full.partition(" ")
        if not rest:
            sys.exit(f"시·군·구 이름이 없습니다: {code} {full!r}")
        rows.append((code, SIDO[code[:2]], rest))
    return rows


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("source", help="법정동코드 전체자료(.zip 또는 .txt)")
    ap.add_argument("--out", default=str(OUT))
    a = ap.parse_args()
    rows = select(read_text(a.source))
    with open(a.out, "w", encoding="utf-8", newline="\n") as f:
        w = csv.writer(f, lineterminator="\n")
        w.writerow(["code", "sido", "name"])
        w.writerows(rows)
    by = {s: sum(1 for r in rows if r[1] == s) for s in SIDO.values()}
    print(f"사는 곳 {len(rows)}곳(" + " · ".join(f"{s} {n}" for s, n in by.items()) + f") → {a.out}")


if __name__ == "__main__":
    main()
