"""extract_outcomes.check — 수기 실습 결과에서 사실 구절만 남기는 거르기 규칙(ADR-0020). 키·네트워크 없이 돈다."""
import unittest

import extract_outcomes as e

RESULTS = ("해외 운송비를 절반 이상 줄일 수 있는 방안을 직접 조사하고 PPT로 제안드린 결과, 해당 방안이 채택되었습니다. "
           "메인 PM으로서 '천안 K-컬처박람회' 기획까지 맡아 진행하였습니다. 정직원 입사를 앞두고 있습니다. "
           "제 노력이 담긴 결과물을 보니 뿌듯했고, 저의 첫 게시글이 업로드 되던 날이 기억에 남습니다. "
           "프로젝트 진행을 맡기도 했습니다.")


class CheckTest(unittest.TestCase):

    def test_원문에_그대로_있는_사실_구절만_남긴다(self):
        kept, dropped = e.check(["해외 운송비를 절반 이상 줄일 수 있는 방안을 직접 조사하고 PPT로 제안",
                                 "해외 운송비를 절반으로 줄임"], RESULTS)
        self.assertEqual(kept, ["해외 운송비를 절반 이상 줄일 수 있는 방안을 직접 조사하고 PPT로 제안"])
        self.assertEqual(dropped[0]["reason"], "원문에 그대로 없음")

    def test_감상_진로_1인칭은_버린다(self):
        kept, dropped = e.check(["정직원 입사", "결과물을 보니 뿌듯", "저의 첫 게시글이 업로드"], RESULTS)
        self.assertEqual(kept, [])
        self.assertEqual({d["reason"] for d in dropped}, {"감상·평가·진로·1인칭 낱말"})

    def test_문장_중간에서_끊긴_꼴은_버린다(self):
        kept, dropped = e.check(["저의 첫 게시글이 업로드 되던 날", "프로젝트 진행을 맡", "해당 방안이 채택되었습니다."],
                                RESULTS)
        self.assertEqual(kept, [])
        self.assertEqual([d["reason"] for d in dropped][1:], ["문장 중간에서 끊김", "문장 중간에서 끊김"])

    def test_감싼_따옴표만_떼고_안쪽_따옴표는_원문대로(self):
        kept, _ = e.check(["\"해당 방안이 채택\"", "'천안 K-컬처박람회' 기획까지 맡아 진행"], RESULTS)
        self.assertEqual(kept, ["해당 방안이 채택", "'천안 K-컬처박람회' 기획까지 맡아 진행"])

    def test_60자를_넘거나_3개를_넘으면_버린다(self):
        long = "해외 운송비를 절반 이상 줄일 수 있는 방안을 직접 조사하고 PPT로 제안드린 결과, 해당 방안이 채택되었습니다. 메인 PM"
        self.assertGreater(len(long), e.MAX_LEN)
        kept, dropped = e.check([long, "해외 운송비", "PPT로 제안", "해당 방안이 채택", "방안을 직접 조사"], RESULTS)
        self.assertEqual(len(kept), 3)
        self.assertEqual([d["reason"] for d in dropped], ["60자 초과", "3개 초과"])

    def test_분할_압축본_이름은_원본_이름으로(self):
        self.assertEqual(e.source_name("[2024-2학기] 수기 모음_part2.pdf"), "[2024-2학기] 수기 모음.pdf")
        self.assertEqual(e.source_name("[2025-1학기] 수기 모음_small.pdf"), "[2025-1학기] 수기 모음.pdf")


if __name__ == "__main__":
    unittest.main()
