"""E8 ③ 분류 결과로 사진을 고르고 서비스 정적 파일과 시드 입력(curated/intro_photos.csv)을 만든다 (ADR-0030)

    python place_photos.py [--out out]

고르는 규칙
- kind가 '사진'인 것만(로고·그래픽·문서·직인서명은 뺀다). 얼굴이 보이는 사진도 그대로 둔다(10/10 결정, 서식에
  '현장실습학기제 홍보자료로 사용될 수 있습니다' 문구).
- 소개서 서식의 '회사 전경 및 활동사진' 칸에 든 사진은 전부. 그 칸인지는 제목 글자(section_header)나 Claude의
  photo_section으로 안다. 회사가 따로 낸 소개 책자·슬라이드 쪽의 그림은 넣지 않는다 — 디자인 조각이 잘게 나뉘어 있어
  '사진'이 아니다.
- 캡션: 글자가 있는 쪽이면 원문에 그대로 있는 문구만 쓴다(TEXT). 글자가 없는 쪽(그림으로만 된 PDF)은 Claude가 읽은
  문구를 쓰고 VISION으로 표시한다 — 사람이 contact_sheet.jpg로 한 번 본다. manual_crops.json으로 자른 것은 MANUAL.

결과
- src/main/resources/static/photos/<기관 id>/<순번>.jpg (기관마다 폴더를 비우고 다시 쓴다)
- seed/curated/intro_photos.csv (커밋) — build_seed.py가 institution_photo·source_document(INTRODUCTION)로 넣는다
- out/contact_sheet.jpg — 사람 확인용(커밋하지 않음)
"""
import argparse, csv, json, pathlib, re, shutil

from PIL import Image, ImageDraw, ImageFont

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parent.parent
NOT_CAPTION = re.compile(r"홍보자료로 사용될 수 있습니다")


def squash(s):
    return re.sub(r"[\s\"'“”‘’·•\-_.,()\[\]]+", "", s or "")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", default=str(HERE / "out"))
    ap.add_argument("--ids", default=str(HERE.parent / "seed" / "curated" / "ids.json"))
    ap.add_argument("--static", default=str(ROOT / "src" / "main" / "resources" / "static" / "photos"))
    ap.add_argument("--csv", default=str(HERE.parent / "seed" / "curated" / "intro_photos.csv"))
    a = ap.parse_args()
    out = pathlib.Path(a.out)
    data = json.loads((out / "candidates.json").read_text(encoding="utf-8"))
    classified = json.loads((out / "classified.json").read_text(encoding="utf-8"))
    ids = json.loads(pathlib.Path(a.ids).read_text(encoding="utf-8"))["institution"]
    docs = {d["doc"]: d for d in data["docs"]}

    picked, dropped = [], {}
    for c in data["candidates"]:
        k = f"{c['doc']:02d}-{c['page']:02d}"
        if c.get("manual"):
            doc = docs[c["doc"]]
            picked.append({**c, "kind": "사진", "scene": "기타", "caption": c["caption_text"],
                           "caption_source": "MANUAL", "doc_title": doc["file"], "doc_pages": doc["pages"]})
            continue
        if k not in classified:
            raise SystemExit(f"{k}: 분류 결과가 없습니다 — classify_photos.py를 다시 돌리세요")
        if not (c["section_header"] or classified[k]["photo_section"]):
            dropped["사진 칸 밖"] = dropped.get("사진 칸 밖", 0) + 1
            continue
        items = {i["n"]: i for i in classified[k]["items"]}
        if c["n"] not in items:
            raise SystemExit(f"{k} #{c['n']}: 분류 응답에 번호가 빠졌습니다 — 그 쪽만 지우고 다시 돌리세요")
        it = items[c["n"]]
        doc = docs[c["doc"]]
        if c["institution"] not in ids:
            raise SystemExit(f"{doc['file']}: 기관 '{c['institution']}'이 ids.json에 없습니다")
        if it["kind"] != "사진":
            dropped[it["kind"]] = dropped.get(it["kind"], 0) + 1
            continue
        caption, source = "", ""
        vision = it["caption"].strip()
        if NOT_CAPTION.search(vision):
            vision = ""
        if c["page_has_text"]:
            ptext = squash((out / "pages" / f"{k}.txt").read_text(encoding="utf-8"))
            if vision and squash(vision) in ptext:
                caption, source = vision, "TEXT"
            elif c["caption_text"] and not NOT_CAPTION.search(c["caption_text"]):
                caption, source = c["caption_text"], "TEXT"
        elif vision:
            caption, source = vision, "VISION"
        picked.append({**c, "kind": it["kind"], "scene": it["scene"], "caption": caption[:100],
                       "caption_source": source, "doc_title": doc["file"], "doc_pages": doc["pages"]})

    static = pathlib.Path(a.static)
    rows, by_inst = [], {}
    for c in sorted(picked, key=lambda c: (ids[c["institution"]], c["doc"], c["page"], c["n"])):
        inst_id = ids[c["institution"]]
        by_inst.setdefault(inst_id, []).append(c)
    for inst_id, items in by_inst.items():
        target = static / str(inst_id)
        if target.exists():
            shutil.rmtree(target)
        target.mkdir(parents=True)
        for seq, c in enumerate(items, 1):
            shutil.copyfile(out / c["crop"], target / f"{seq}.jpg")
            rows.append({"institution_id": inst_id, "seq": seq, "doc_title": c["doc_title"], "doc_pages": c["doc_pages"],
                         "page": c["page"], "scene": c["scene"], "caption": c["caption"],
                         "caption_source": c["caption_source"], "width": c["width"], "height": c["height"]})
    with open(a.csv, "w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0]))
        w.writeheader()
        w.writerows(rows)

    # 사람 확인용 한 장(기관 id · 순번 · 캡션)
    thumbs = []
    for r in rows:
        im = Image.open(static / str(r["institution_id"]) / f"{r['seq']}.jpg")
        im.thumbnail((220, 160))
        thumbs.append((r, im))
    cols = 8
    sheet = Image.new("RGB", (cols * 230, ((len(thumbs) + cols - 1) // cols) * 200), "white")
    draw = ImageDraw.Draw(sheet)
    try:
        font = ImageFont.truetype("NanumGothic.ttf", 12)
    except OSError:
        font = ImageFont.load_default()
    for i, (r, im) in enumerate(thumbs):
        x, y = (i % cols) * 230, (i // cols) * 200
        sheet.paste(im, (x + 5, y + 5))
        draw.text((x + 5, y + 168), f"{r['institution_id']}-{r['seq']} {r['caption_source']} {r['caption'][:14]}",
                  fill=(0, 0, 0), font=font)
    sheet.save(out / "contact_sheet.jpg", "JPEG", quality=70)
    print(f"사진 {len(rows)}장 · 기관 {len(by_inst)}곳 · 뺀 것 {dropped} → {a.csv}, {static}")


if __name__ == "__main__":
    main()
