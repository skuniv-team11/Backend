"""E8 ① 실습기관 소개서(별지 제1-2호) → 사진 후보 + 번호 상자를 그린 쪽 그림 (ADR-0030)

    python extract_photos.py "<기관별 운영계획서 및 소개서 폴더>" [--out out]

- 폴더 아래 '*소개서*.pdf'만 본다(운영계획서·홍보자료는 보지 않는다).
- 쪽마다 PyMuPDF가 알려 주는 이미지 자리(bbox)를 후보로 잡는다. 쪽 넓이의 1.2%보다 작거나(아이콘·글머리 그림)
  85%보다 큰 것(쪽 전체를 찍은 스캔)은 뺀다. 같은 문서에서 모양이 같은 그림은 한 번만 둔다.
- 후보는 그 자리를 쪽 그대로 다시 그려 자른다(마스크·회전이 화면과 같게). 긴 변 1000px, JPEG.
- section_header: 그 쪽에 '회사 전경 및 활동사진' 제목 글자가 있는가(글자가 있는 PDF만 안다).
- 캡션 원문: 글자가 있는 쪽이면 그림 바로 아래(없으면 위) 한 줄을 그대로 옮긴다. 글자가 없는 쪽(그림으로만 된 PDF)은
  classify_photos.py가 읽는다.
- manual_crops.json: 쪽 전체가 한 장의 스캔이라 그림 자리를 모르는 소개서는 사람이 적은 자리·캡션으로 자른다(manual).
- 결과: out/candidates.json, out/crops/<문서 번호>/<쪽>-<번호>.jpg, out/pages/<문서 번호>-<쪽>.jpg(번호 상자)
"""
import argparse, hashlib, io, json, pathlib, sys

import fitz  # PyMuPDF
from PIL import Image, ImageDraw, ImageFont

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "common"))
from jobs_sheet import canon  # noqa: E402

MIN_AREA, MAX_AREA = 0.012, 0.85
MIN_SIDE_PT = 40
LONG_SIDE = 1000
PAGE_LONG_SIDE = 1400
SECTION_WORDS = ("회사 전경", "회사전경", "활동사진", "활동 사진")


def ahash(img):
    g = img.convert("L").resize((16, 16))
    px = list(g.getdata())
    avg = sum(px) / len(px)
    return "".join("1" if p > avg else "0" for p in px)


def caption_near(words, box):
    """그림 바로 아래(없으면 바로 위) 한 줄 글자. 그림 가로 범위 안에 가운데가 든 낱말만."""
    def line(y0, y1):
        picked = [w for w in words if y0 <= (w[1] + w[3]) / 2 <= y1
                  and box.x0 - 8 <= (w[0] + w[2]) / 2 <= box.x1 + 8]
        picked.sort(key=lambda w: (round(w[1]), w[0]))
        return " ".join(w[4] for w in picked).strip()
    return line(box.y1, box.y1 + 28) or line(box.y0 - 28, box.y0)


def render(page, clip, long_side):
    zoom = min(6.0, long_side / max(clip.width, clip.height))
    pix = page.get_pixmap(matrix=fitz.Matrix(zoom, zoom), clip=clip, alpha=False)
    return Image.open(io.BytesIO(pix.tobytes("png"))).convert("RGB")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("folder")
    ap.add_argument("--out", default=str(HERE / "out"))
    a = ap.parse_args()
    out = pathlib.Path(a.out)
    (out / "crops").mkdir(parents=True, exist_ok=True)
    (out / "pages").mkdir(parents=True, exist_ok=True)
    try:
        font = ImageFont.truetype("DejaVuSans-Bold.ttf", 34)
    except OSError:
        font = ImageFont.load_default()

    manual = {}
    for m in json.loads((HERE / "manual_crops.json").read_text(encoding="utf-8"))["crops"]:
        manual.setdefault(m["file"], []).append(m)
    files = sorted(p for p in pathlib.Path(a.folder).rglob("*소개서*.pdf"))
    docs, cands = [], []
    for d, pdf in enumerate(files, 1):
        key = canon(pdf.name.split("_")[0])
        doc = fitz.open(pdf)
        has_text = sum(len(p.get_text().strip()) for p in doc) > 50
        docs.append({"doc": d, "file": pdf.name, "institution": key, "pages": doc.page_count, "has_text": has_text})
        seen = set()
        manual_pages = {m["page"] for m in manual.get(pdf.name, [])}
        for pno, page in enumerate(doc, 1):
            if pno in manual_pages:
                continue          # 사람이 자리를 적은 쪽은 자동 후보를 쓰지 않는다
            ptext = page.get_text()
            section = any(w in ptext for w in SECTION_WORDS)
            words = page.get_text("words")
            area = page.rect.width * page.rect.height
            boxes = []
            for info in page.get_image_info(xrefs=True):
                box = fitz.Rect(info["bbox"]) & page.rect
                if box.is_empty or min(box.width, box.height) < MIN_SIDE_PT:
                    continue
                ratio = box.width * box.height / area
                if not MIN_AREA <= ratio <= MAX_AREA:
                    continue
                img = render(page, box, LONG_SIDE)
                h = ahash(img)
                if h in seen:
                    continue
                seen.add(h)
                boxes.append((box, img))
            # 읽는 순서: 위 → 아래, 같은 줄은 왼쪽 → 오른쪽
            boxes.sort(key=lambda b: (round(b[0].y0 / 20), b[0].x0))
            if not boxes:
                continue
            page_img = render(page, page.rect, PAGE_LONG_SIDE)
            draw = ImageDraw.Draw(page_img)
            sx = page_img.width / page.rect.width
            for n, (box, img) in enumerate(boxes, 1):
                rel = f"crops/{d:02d}/{pno:02d}-{n}.jpg"
                (out / rel).parent.mkdir(parents=True, exist_ok=True)
                img.save(out / rel, "JPEG", quality=82, optimize=True)
                draw.rectangle([box.x0 * sx, box.y0 * sx, box.x1 * sx, box.y1 * sx], outline=(230, 0, 0), width=5)
                draw.rectangle([box.x0 * sx, box.y0 * sx, box.x0 * sx + 52, box.y0 * sx + 44], fill=(230, 0, 0))
                draw.text((box.x0 * sx + 10, box.y0 * sx + 2), str(n), fill=(255, 255, 255), font=font)
                cands.append({"doc": d, "institution": key, "file": pdf.name, "page": pno, "n": n,
                              "bbox": [round(v, 1) for v in box], "crop": rel, "width": img.width,
                              "height": img.height, "section_header": section, "page_has_text": len(ptext.strip()) > 20,
                              "caption_text": caption_near(words, box),
                              "sha256": hashlib.sha256(img.tobytes()).hexdigest()[:16]})
            page_img.save(out / "pages" / f"{d:02d}-{pno:02d}.jpg", "JPEG", quality=80)
            (out / "pages" / f"{d:02d}-{pno:02d}.txt").write_text(ptext, encoding="utf-8")
        for n, m in enumerate(manual.get(pdf.name, []), 1):
            page = doc[m["page"] - 1]
            r = page.rect
            box = fitz.Rect(r.x0 + m["box"][0] * r.width, r.y0 + m["box"][1] * r.height,
                            r.x0 + m["box"][2] * r.width, r.y0 + m["box"][3] * r.height)
            img = render(page, box, LONG_SIDE)
            rel = f"crops/{d:02d}/{m['page']:02d}-m{n}.jpg"
            (out / rel).parent.mkdir(parents=True, exist_ok=True)
            img.save(out / rel, "JPEG", quality=82, optimize=True)
            cands.append({"doc": d, "institution": key, "file": pdf.name, "page": m["page"], "n": 100 + n,
                          "bbox": [round(v, 1) for v in box], "crop": rel, "width": img.width, "height": img.height,
                          "section_header": True, "page_has_text": False, "caption_text": m["caption"],
                          "manual": True, "sha256": hashlib.sha256(img.tobytes()).hexdigest()[:16]})
    (out / "candidates.json").write_text(json.dumps({"docs": docs, "candidates": cands}, ensure_ascii=False, indent=1),
                                         encoding="utf-8")
    print(f"소개서 {len(docs)}개 · 후보 {len(cands)}개 · 쪽 {len({(c['doc'], c['page']) for c in cands})}개 → {out}")


if __name__ == "__main__":
    main()
