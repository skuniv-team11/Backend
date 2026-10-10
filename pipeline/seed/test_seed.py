"""pipeline/seed 단위 테스트. scripts/check_pipeline.py가 돌린다(python -m unittest discover -s pipeline/seed)."""
import datetime as dt
import json
import unittest

import build_seed as b
import replay
import to_sql

D = dt.date
START, END = D(2026, 7, 13), D(2026, 7, 24)


class ReplayTest(unittest.TestCase):
    def cases(self):
        for job_id in range(1, 60):
            for hc, fa in ((1, 0), (1, 2), (2, 3), (3, 1), (2, 0), (5, 5)):
                for closes in (None, D(2026, 7, 18), D(2026, 7, 14), D(2026, 7, 24)):
                    yield job_id, hc, fa, closes

    def test_관심_합은_배정_수_마감일부터는_행이_없다(self):
        for job_id, hc, fa, closes in self.cases():
            rows = replay.job_signals(job_id, hc, fa, START, END, closes, "t")
            self.assertEqual(sum(r[1] for r in rows), fa)
            for day, interest in rows:
                self.assertGreater(interest, 0)
                self.assertTrue(START <= day <= END)
                if closes:
                    self.assertLess(day, closes)
            self.assertEqual([r[0] for r in rows], sorted({r[0] for r in rows}))

    def test_같은_seed면_같은_값_다른_seed면_다른_값(self):
        jobs = [{"id": i, "headcount": 2, "final_assigned": 3, "closes_on": None} for i in range(1, 30)]
        self.assertEqual(replay.generate(jobs, START, END, "2026-2"), replay.generate(jobs, START, END, "2026-2"))
        self.assertNotEqual(replay.generate(jobs, START, END, "2026-2")[0], replay.generate(jobs, START, END, "x")[0])

    def test_날짜_없는_센터_모집마감은_회차_안에서_가상_마감일을_정하고_그_전까지만_신호(self):
        jobs = [{"id": i, "headcount": 2, "final_assigned": 2, "closes_on": None, "center_closed_undated": True}
                for i in range(1, 40)]
        signals, virtual = replay.generate(jobs, START, END, "2026-2")
        self.assertEqual(set(virtual), set(range(1, 40)))
        for job_id, close in virtual.items():
            self.assertTrue(START < close < END)
            days = [s["signal_date"] for s in signals if s["job_id"] == job_id]
            self.assertTrue(all(d < close.isoformat() for d in days))
            self.assertEqual(sum(s["interest_count"] for s in signals if s["job_id"] == job_id), 2)

    def test_열린_날이_없는데_배정이_있으면_멈춘다(self):
        self.assertEqual(replay.job_signals(1, 1, 0, START, END, START, "t"), [])
        with self.assertRaises(ValueError):
            replay.job_signals(1, 1, 1, START, END, START, "t")


class ToSqlTest(unittest.TestCase):
    def test_값_표기(self):
        self.assertEqual(to_sql.lit("job", "title", "O'Neil"), "'O''Neil'")
        self.assertEqual(to_sql.lit("job", "title", None), "NULL")
        self.assertEqual(to_sql.lit("job", "major_open", False), "FALSE")
        self.assertEqual(to_sql.lit("job", "gpa_min", 3.5), "3.5")
        self.assertEqual(to_sql.lit("job", "weekly_hours", 40.0), "40.0")
        self.assertEqual(to_sql.lit("job", "closes_on", "2026-07-18"), "DATE '2026-07-18'")
        self.assertEqual(to_sql.lit("job", "weekdays", ["MON", "TUE"]), "ARRAY['MON', 'TUE']::varchar(3)[]")
        self.assertEqual(to_sql.lit("job", "benefits", []), "'{}'::varchar(20)[]")
        self.assertEqual(to_sql.lit("testimonial", "activities", ["a'b"]), "ARRAY['a''b']::text[]")
        self.assertEqual(to_sql.lit("testimonial", "outcomes", []), "'{}'::text[]")

    def test_위험한_값은_막는다(self):
        for bad in ("${x}", "a\r\nb"):
            with self.assertRaises(ValueError):
                to_sql.lit("job", "title", bad)
        with self.assertRaises(ValueError):
            to_sql.lit("job", "closes_on", "2026-13-01")
        with self.assertRaises(ValueError):
            to_sql.render({"program": [{"id": 1, "code": "X", "name": "n", "extra": 1}]})

    def test_부모는_upsert와_없는_행_삭제_자식은_통째로_교체(self):
        seed = {"program": [{"id": 1, "code": "X", "name": "n"}],
                "major_alias": [{"id": 1, "label": "경영학부"}]}
        sql = to_sql.render(seed)
        self.assertIn("ON CONFLICT (id) DO UPDATE SET code = EXCLUDED.code, name = EXCLUDED.name;", sql)
        self.assertIn("DELETE FROM major_alias;", sql)
        self.assertIn("DELETE FROM job WHERE id NOT IN (NULL);", sql)       # job이 비면 전부 지운다
        self.assertIn("DELETE FROM area a WHERE a.code NOT IN (NULL)", sql)  # area가 비면 프로필이 안 쓰는 행은 지운다
        self.assertLess(sql.index("DELETE FROM major_alias;"), sql.index("INSERT INTO program"))

    def test_사는_곳은_code로_upsert하고_프로필이_쓰는_행은_남긴다(self):
        seed = {"area": [{"code": "11350", "sido": "서울", "name": "노원구", "sort_order": 1}]}
        sql = to_sql.render(seed)
        self.assertIn("ON CONFLICT (code) DO UPDATE SET sido = EXCLUDED.sido, name = EXCLUDED.name, "
                      "sort_order = EXCLUDED.sort_order;", sql)
        self.assertIn("DELETE FROM area a WHERE a.code NOT IN ('11350')\n"
                      "  AND NOT EXISTS (SELECT 1 FROM student_profile sp WHERE sp.home_area_code = a.code);", sql)
        # 이름·순서 유니크가 upsert 중에 부딪히지 않게 비켜 두고, 남은 행은 목록 맨 뒤로
        self.assertLess(sql.index("UPDATE area SET sort_order = -sort_order;"), sql.index("INSERT INTO area"))
        self.assertLess(sql.index("INSERT INTO area"), sql.index("UPDATE area a SET sort_order = 30000 + s.n"))

    def test_사는_곳_목록은_서울_인천_경기_시군구_코드만(self):
        import build_seed
        rows = build_seed.area_rows([{"code": "41111", "sido": "경기", "name": "수원시 장안구"},
                                     {"code": "11110", "sido": "서울", "name": "종로구"}])
        self.assertEqual([r["code"] for r in rows], ["11110", "41111"])   # 코드 순
        self.assertEqual([r["sort_order"] for r in rows], [1, 2])
        with self.assertRaises(SystemExit):
            build_seed.area_rows([{"code": "26110", "sido": "부산", "name": "중구"}])

    def test_자격증은_code로_upsert하고_직무_upsert_뒤에_프로필이_안_쓰는_코드만_지운다(self):
        seed = {"certificate": [{"code": "BEAUTY", "label": "미용 자격증·면허증", "sort_order": 1}]}
        sql = to_sql.render(seed)
        self.assertIn("INSERT INTO certificate (code, label, sort_order) VALUES\n  ('BEAUTY', '미용 자격증·면허증', 1)\n"
                      "ON CONFLICT (code) DO UPDATE SET label = EXCLUDED.label, sort_order = EXCLUDED.sort_order;", sql)
        self.assertIn("DELETE FROM certificate c WHERE c.code NOT IN ('BEAUTY')", sql)
        self.assertIn("NOT EXISTS (SELECT 1 FROM student_profile sp WHERE c.code = ANY (sp.certificates));", sql)
        self.assertLess(sql.index("-- 4. 부모 테이블 upsert"), sql.index("DELETE FROM certificate"))

    def test_해더의_해시는_seed와_ncs와_본문을_따른다(self):
        text = json.dumps({"program": [{"id": 1, "code": "X", "name": "n"}]})
        ncs = json.dumps({"occupation": [{"code": "0241", "name": "광고 및 홍보 전문가"}]}, ensure_ascii=False)
        a, c = to_sql.build(text, ncs), to_sql.build(text.replace('"n"', '"m"'), ncs)
        self.assertNotEqual(a.splitlines()[2], c.splitlines()[2])
        self.assertEqual(a.splitlines()[3], c.splitlines()[3])
        self.assertNotEqual(a.splitlines()[4], c.splitlines()[4])
        d = to_sql.build(text, ncs.replace("광고", "홍보"))
        self.assertNotEqual(a.splitlines()[3], d.splitlines()[3])
        with self.assertRaises(ValueError):
            to_sql.build(text, text)   # 같은 테이블이 두 파일에 있으면 실패


class NormalizeTest(unittest.TestCase):
    def test_접수마감은_그날_안의_시각이면_다음_날부터_0시면_그날부터_지원_불가(self):
        self.assertEqual(b.plan_deadline("7월 23일 24시", 2026), D(2026, 7, 24))
        self.assertEqual(b.plan_deadline("7월 24일 18시", 2026), D(2026, 7, 25))
        self.assertEqual(b.plan_deadline("07월24일", 2026), D(2026, 7, 25))
        self.assertEqual(b.plan_deadline("7월 20일 0시", 2026), D(2026, 7, 20))
        self.assertIsNone(b.plan_deadline("00월00일00시 [ ] 일정별도협의[ V ]", 2026))
        self.assertIsNone(b.plan_deadline("일정별도협의", 2026))
        self.assertEqual(b.list_deadline("*모집마감\n- 서류마감일 : 26. 7. 17. 12:00"), D(2026, 7, 18))

    def test_리스트_값(self):
        cell = "■ 근로기간 : 26. 9. 1. ~ 12. 12.\n■ 근로요일 : 월, 화, 수, 목, 금\n■ 근로시간 : 09:00 ~ 18:00\n※ 금 : 09:00 ~ 17:30"
        self.assertEqual(b.list_period(cell, 2026), (D(2026, 9, 1), D(2026, 12, 12)))
        self.assertEqual(b.list_period("26. 11. 1. ~ 2. 28.", 2026), (D(2026, 11, 1), D(2027, 2, 28)))
        self.assertEqual(b.list_hours(cell), "09:00 ~ 18:00 (금 09:00 ~ 17:30)")
        self.assertEqual(b.won("1,618,000원"), 1618000)
        self.assertEqual(b.display_name("(주) 세정"), "세정")
        self.assertEqual(b.display_name("로젠 주식회사"), "로젠")
        self.assertEqual(b.display_name("(주)미디어 코퍼스"), "미디어 코퍼스")

    def test_계획서_값은_확실할_때만(self):
        self.assertEqual(b.hhmm_plan("오전 9시 00분 ~ 오후 6 시 00분"), ["09:00", "18:00"])
        self.assertEqual(b.hhmm_plan("오전 8시 30분 ~ 오후 17시 30분"), ["08:30", "17:30"])
        self.assertEqual(b.plan_grade("3,4학년"), "Y3_4")
        self.assertEqual(b.plan_grade("4학년(졸업예정)"), "GRADUATING")
        self.assertEqual(b.plan_grade("4 학년"), "Y4")
        self.assertIsNone(b.plan_grade("4학년 선호 (아니여도 무방)"))
        self.assertIsNone(b.plan_grade("학년 무관"))
        self.assertEqual(b.plan_gpa("3.00이상 / 4.5만점"), 3.0)
        self.assertEqual(b.plan_gpa("3.0/4.5 이상"), 3.0)
        self.assertIsNone(b.plan_gpa("학점 무관"))
        self.assertEqual(b.plan_headcount("중 2명"), 2)
        self.assertEqual(b.plan_headcount("(2명)"), 2)
        self.assertEqual(b.plan_headcount("1인"), 1)

    def test_자격증_언급은_무관이_아니면_잡는다(self):
        said = lambda t: b.CERT_MENTION.search(b.CERT_NOT_REQUIRED.sub("", t)) is not None  # noqa: E731
        self.assertTrue(said("미용 자격증 or 미용 면허증 소지자"))
        self.assertTrue(said("상경계열(경영학과, 회계학과) 또는 회계/자금 자격증 취득자"))
        self.assertFalse(said("워드/엑셀/파워포인트 : 중 외국어 : 무관 자격증 : 무관"))
        self.assertFalse(said("1. 자격사항 영상 및 디자인 관련 전공"))

    def test_자격증_고침은_코드가_있고_인용이_추출_원문_안에_있어야_한다(self):
        pj = {"competencies": {"value": "*Office 활용능력 중급 이상\n*회계관련 자격증 취득 및 지식(전공자) 함양",
                               "page": 2, "quote": "*Office 활용능력 중급 이상"},
              "certificate": {"value": "언급 없음", "page": 0, "quote": ""}}
        ok = {"level": "PREFERRED", "code": "ACCOUNTING", "text": "회계관련 자격증 취득 및 지식(전공자) 함양",
              "page": 2, "quote": "*회계관련 자격증 취득 및 지식(전공자) 함양"}
        level, code, txt, ev = b.certificate_override("로젠|회계팀", {"certificate": ok}, pj, {"ACCOUNTING"}, 3)
        self.assertEqual((level, code, txt), ("PREFERRED", "ACCOUNTING", "회계관련 자격증 취득 및 지식(전공자) 함양"))
        self.assertEqual(ev, {"value": txt, "page": 2, "quote": "*회계관련 자격증 취득 및 지식(전공자) 함양"})
        for bad in ({**ok, "quote": "전산회계 1급 소지자"}, {**ok, "code": "BEAUTY"}, {**ok, "level": "NONE"},
                    {**ok, "page": 4}):
            with self.assertRaises(SystemExit):
                b.certificate_override("로젠|회계팀", {"certificate": bad}, pj, {"ACCOUNTING"}, 3)

    def test_판정_출처는_리스트_칸의_그_줄을_글머리표만_떼고(self):
        cell = "■ 전공: 광고홍보콘텐츠학과\n■ 학년: 4학년"
        self.assertEqual(b.list_quote(cell, r"학년"), "학년: 4학년")
        self.assertEqual(b.list_quote(cell, r"전공"), "전공: 광고홍보콘텐츠학과")
        note = "*모집마감\n- 서류마감일 : 26. 7. 17. 12:00\n- 포트폴리오 필수 제출"
        self.assertEqual(b.list_quote(note, r"포트폴리오[^\n]*필수"), "포트폴리오 필수 제출")
        self.assertIsNone(b.list_quote(note, r"학점\s*\d"))

    def test_근거_필드_이름이_V1_허용_목록과_같다(self):
        b.check_allowed_keys()


class AlertNotationTest(unittest.TestCase):
    """검토 알림: 표기만 다른 차이는 알리지 않는다(ADR-0030)."""

    def test_띄어쓰기_끝의_외_포함_관계는_표기_차이(self):
        self.assertTrue(b.trivial_difference("광고영화 및 비디오물 제작업", "광고 영화 및 비디오물 제작업 외"))
        self.assertTrue(b.trivial_difference("응용소프트웨어 개발 및 공급업", "소프트웨어 개발 및 공급업"))
        self.assertTrue(b.trivial_difference("정보통신업", "정보통신업 외"))

    def test_글자_한두_개_오타는_표기_차이(self):
        self.assertTrue(b.trivial_difference("마케팅 콘텐츠 기획 및 운영", "마케팅 컨텐츠 기획 및 운영"))

    def test_숫자가_다르면_표기_차이가_아니다(self):
        self.assertFalse(b.trivial_difference("한국표준산업분류코드 743002", "한국표준산업분류코드 71310"))
        self.assertFalse(b.trivial_difference("2026년 9월 1일 ~ 2026년 12월 12일", "* 15~16주차: 제작한 콘텐츠"))
        self.assertFalse(b.trivial_difference("[1,700,000]원", "1,617,660원"))

    def test_뜻이_다른_전공은_표기_차이가_아니다(self):
        self.assertFalse(b.trivial_difference("컴퓨터공학 전공 학생들이 실제 산업 현장에서", "소프트웨어학과"))
        self.assertFalse(b.trivial_difference("무대패션전공, 광고홍보콘텐츠학과, 영화영상학과, 경영학부",
                                              "무대패션전공, 광고홍보콘텐츠학과, 경영학부"))


class NcsSeedTest(unittest.TestCase):
    def test_구버전을_빼고_같은_이름은_최신_개정만(self):
        import csv, tempfile, ncs_seed
        rows = [("0201030101_14v2", "마케팅전략 계획수립(구버전)", "6"), ("0201030110_16v3", "STP전략 수립", "5"),
                ("0201030116_21v4", "STP 전략 수립", "4"), ("0201030102_21v5", "신상품 기획", "0")]
        with tempfile.NamedTemporaryFile("w", suffix=".csv", delete=False, encoding="utf-8-sig", newline="") as f:
            w = csv.writer(f)
            w.writerow(["ncsClCd", "ncsLclasCdnm", "ncsMclasCdnm", "ncsSclasCdnm", "ncsSubdCdnm", "compeUnitName",
                        "compeUnitLevel", "compeUnitDef"])
            for code, name, level in rows:
                w.writerow([code, "경영·회계·사무", "기획사무", "마케팅", "마케팅전략기획", name, level, "정의"])
        subs, units = ncs_seed.read_units(f.name)
        got = [(u["code"], u["level"]) for u in units["02010301"]]
        self.assertEqual(got, [("0201030102_21v5", None), ("0201030116_21v4", 4)])
        self.assertEqual(subs["02010301"]["small_code"], "020103")


if __name__ == "__main__":
    unittest.main()
