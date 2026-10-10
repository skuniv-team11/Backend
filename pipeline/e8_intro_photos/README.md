# E8 실습기관 소개서 사진 (ADR-0030)

실습기관 소개서(별지 제1-2호)의 **'회사 전경 및 활동사진'** 칸 사진을 직무 상세에 보여 주려고 뽑는다. 결과는 `src/main/resources/static/photos/{기관 id}/{순번}.jpg`(로고와 같은 방식, ADR-0019)와 `seed/curated/intro_photos.csv`(커밋)다. 서비스는 실행 중에 이 파일만 내보낸다.

```
pip install pymupdf pillow
python extract_photos.py "<원본>/2. 기관별 운영계획서 및 소개서" --out out        # ① 후보 + 번호 상자 그림
ANTHROPIC_API_KEY=... python classify_photos.py --out out                          # ② 쪽마다 Claude 1번(이미지 + 구조화 출력)
python place_photos.py --out out                                                   # ③ 고르기 → static/photos, curated/intro_photos.csv
python ../seed/build_seed.py ...  &&  python ../seed/to_sql.py                     # ④ 시드(institution_photo)
```

| 단계 | 하는 일 |
|---|---|
| ① `extract_photos.py` | `*소개서*.pdf`만 본다. 쪽마다 PyMuPDF의 그림 자리 중 쪽 넓이 1.2~85%를 후보로 잡아 쪽 그대로 다시 그려 자른다(긴 변 1,000px). 같은 문서에서 모양이 같은 그림은 한 번. 캡션은 글자 층이 있으면 그림 바로 아래(없으면 위) 한 줄. 쪽 그림에 빨간 번호 상자를 그린다 |
| ② `classify_photos.py` | 번호마다 `kind`(사진·로고·그래픽·문서·직인서명) · `scene` · 인쇄된 `caption` 원문, 쪽마다 `photo_section`(서식의 사진 칸인가). 스키마는 `build_schema.py`(ADR-0003), 프롬프트는 `prompt_system.md`. 이미 한 쪽은 다시 부르지 않는다 |
| ③ `place_photos.py` | 사진 칸(제목 글자 또는 `photo_section`)에 든 `사진`만. 얼굴이 보이는 사진도 둔다(서식의 '홍보자료로 사용될 수 있습니다'). 캡션은 글자 층이 있으면 원문에 그대로 있는 것만(`TEXT`), 없으면 Claude가 읽은 것(`VISION`), 사람이 적은 것(`MANUAL`). 사람 확인용 `out/contact_sheet.jpg` |

- **사람이 자리를 적는 쪽** — `manual_crops.json`: 쪽 전체가 한 장의 스캔(제이숲)이거나 사진이 가는 띠로 잘려 들어간 쪽(빌딩닥터)은 그림 자리를 PDF에서 알 수 없다. 쪽 너비·높이 비율로 자리와 캡션을 적으면 ①이 그 쪽의 자동 후보 대신 쓴다.
- **넣지 않는 것**: 회사가 서식 대신 낸 소개 책자·슬라이드 쪽(더에스엠씨·세정·로젠), 따로 낸 홍보자료 PDF, 로고·도형·문서 그림·직인.
- 2026-10-10: 소개서 18개 → 후보 605개 → 사진 55장(14곳, 5.6MB). 분류 96쪽 약 $0.84(Sonnet 5.5).
- 결과물(`out/`)은 커밋하지 않는다. 원본 PDF는 저장소 밖에 둔다.
