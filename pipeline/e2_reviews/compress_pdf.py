"""PDF를 쪽 이미지(JPEG)로 다시 만들어 용량을 줄인다. Claude 요청 한도(32MB, base64 포함)용.

사용법: python compress_pdf.py <입력.pdf> [--dpi 150] [--quality 80]
결과: <입력 파일명>_small.pdf (같은 폴더)
"""
import argparse, pathlib
import pymupdf

ap = argparse.ArgumentParser()
ap.add_argument("pdf")
ap.add_argument("--dpi", type=int, default=150)
ap.add_argument("--quality", type=int, default=80)
a = ap.parse_args()

src = pathlib.Path(a.pdf)
dst = src.with_name(src.stem + "_small.pdf")
doc, out = pymupdf.open(src), pymupdf.open()
for page in doc:
    pix = page.get_pixmap(dpi=a.dpi)
    img = pix.tobytes("jpeg", jpg_quality=a.quality)
    p = out.new_page(width=page.rect.width, height=page.rect.height)
    p.insert_image(p.rect, stream=img)
out.save(dst, garbage=4, deflate=True)
mb = lambda p: p.stat().st_size / 1e6
print(f"{src.name}: {mb(src):.1f}MB → {dst.name}: {mb(dst):.1f}MB (base64 약 {mb(dst) * 4 / 3:.1f}MB), {len(out)}쪽")
