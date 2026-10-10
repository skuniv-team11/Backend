package kr.ac.skuniv.coopradar.explore;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import kr.ac.skuniv.coopradar.explore.ExploreDrafts.PhraseDraft;
import kr.ac.skuniv.coopradar.explore.ExploreDrafts.PointDraft;
import kr.ac.skuniv.coopradar.explore.ExploreDrafts.WhyDraft;
import kr.ac.skuniv.coopradar.explore.ExploreText.Field;
import kr.ac.skuniv.coopradar.explore.ExploreText.Student;
import kr.ac.skuniv.coopradar.job.InstitutionRef;
import org.junit.jupiter.api.Test;

/** 직무 탐색 규칙(가리기·카드·구절 대조·문장 규칙). DB·AI 없이. */
class ExploreUnitTest {

    static final JobDoc DOC = new JobDoc(122, 22, new InstitutionRef(7, "소서(가상)"), "마케팅팀", "국내 마케팅",
            "*소서(가상) 브랜드 콘텐츠 제작 및 레퍼런스 서치\n*자사 SNS 채널 콘텐츠 운영 및 홍보 마케팅 업무 지원\n"
                    + "-학생 본인이 직접 MD업무에 참여하여 상품MD에 대한 이해를 높이고자 함",
            "현직자와의 Co-work을 통해 실무 기회 부여", "오피스 프로그램 우수자, 뷰티 SNS를 운영해 본 경험이 있는 자",
            List.of("회사 소개 및 기본 업무 교육 실시", "자사 인스타그램 운영 방식 파악/현재 뷰티 트렌드 및 경쟁사 제품 분석"),
            4, 4, 4, 4, "소서(가상) 운영계획서");

    @Test
    void 이메일_전화번호_긴_숫자는_가린다() {
        assertThat(ExploreText.mask("연락 010-1234-5678, 메일 a.b@skuniv.ac.kr, 학번 20231234 입니다"))
                .isEqualTo("연락 [가림], 메일 [가림], 학번 [가림] 입니다");
        assertThat(ExploreText.mask("저장 수가 1년 동안 2배가 됐어요")).isEqualTo("저장 수가 1년 동안 2배가 됐어요");
    }

    @Test
    void 카드는_하는_일_항목만_원문_그대로_기관_이름은_회사() {
        assertThat(ExploreText.cardTexts(DOC)).containsExactly("회사 브랜드 콘텐츠 제작 및 레퍼런스 서치",
                "자사 SNS 채널 콘텐츠 운영 및 홍보 마케팅 업무 지원");
        // 개요 항목이 모자라면 주차 계획 항목(회사 소개·교육은 뺀다)
        JobDoc thin = new JobDoc(103, 3, new InstitutionRef(3, "오뷔엘알(가상)"), "마케팅팀", "인플루언서",
                "[마케팅팀(1)-국내 인플루언서 관리 및 마케팅 제반 업무]", null, null,
                List.of("브랜드 소개 및 마케팅 업무 소개, 시딩 리스트업", "인플루언서 소통 및 관리",
                        "중국 패션 셀러, KOL, 라이브커머스 채널 등을 조사"), 2, 2, 2, 2, "가상 운영계획서");
        assertThat(ExploreText.cardTexts(thin)).containsExactly("국내 인플루언서 관리 및 마케팅 제반 업무", "시딩 리스트업",
                "인플루언서 소통 및 관리", "중국 패션 셀러, KOL, 라이브커머스 채널 등을 조사");
    }

    @Test
    void 거의_같은_카드는_하나로_합치고_직무를_번갈아_놓는다() {
        JobDoc other = new JobDoc(123, 23, new InstitutionRef(7, "소서(가상)"), "마케팅팀", "해외 마케팅",
                "*자사 SNS 콘텐츠 운영 및 홍보 마케팅 업무 지원\n*해외 바이어 응대 및 자료 번역", null, null, List.of(),
                4, 4, 4, 4, "소서(가상) 운영계획서");
        List<ExploreDtos.Card> cards = ExploreText.cards(List.of(DOC, other));
        // '자사 SNS 채널 콘텐츠 운영…'(122-2)은 먼저 놓인 '자사 SNS 콘텐츠 운영…'(123-1)과 거의 같아(자카드 0.82) 빠진다
        assertThat(cards).extracting(ExploreDtos.Card::id).containsExactly("122-1", "123-1", "123-2");
        assertThat(ExploreText.jaccard(ExploreText.bigrams("자사 SNS 채널 콘텐츠 운영 및 홍보 마케팅 업무 지원"),
                ExploreText.bigrams("자사 SNS 콘텐츠 운영 및 홍보 마케팅 업무 지원"))).isGreaterThanOrEqualTo(0.8);
        assertThat(ExploreText.cards(List.of(DOC, DOC))).hasSize(2);
    }

    @Test
    void 구절은_한_조각_안에_그대로_있어야_한다() {
        Student s = new Student(List.of("인스타그램 계정을 1년 동안 운영했어요."), List.of("인플루언서 소통 및 관리"), "뷰티");
        assertThat(ExploreVerifier.studentQuote("인스타그램 계정을 1년 동안 운영했어요", s)).isPresent();
        assertThat(ExploreVerifier.studentQuote("“인스타그램 계정을 1년 동안”", s)).isPresent();
        // 두 조각에 걸치면 안 된다
        assertThat(ExploreVerifier.studentQuote("운영했어요 인플루언서 소통", s)).isEmpty();
        assertThat(ExploreVerifier.jobPiece("자사 SNS 채널 콘텐츠 운영", DOC)).get().extracting(ExploreText.Piece::field)
                .isEqualTo(Field.OVERVIEW);
        assertThat(ExploreVerifier.jobPiece("현재 뷰티 트렌드 및 경쟁사 제품 분석", DOC)).get()
                .extracting(ExploreText.Piece::field).isEqualTo(Field.WEEKLY);
        // 주차 항목 둘을 ' / '로 붙인 구절은 안 된다(E7에서 틀린 유형)
        assertThat(ExploreVerifier.jobPiece("회사 소개 및 기본 업무 교육 실시 / 자사 인스타그램", DOC)).isEmpty();
        assertThat(DOC.page(Field.WEEKLY)).isEqualTo(4);
        assertThat(DOC.page(Field.TITLE)).isNull();
    }

    @Test
    void 문장은_해요체이고_습니다_당신_선호_전공이_없어야_한다() {
        assertThat(ExploreVerifier.sentence("게시 시간을 바꿔 본 경험이 SNS 운영에 이어져요.")).isPresent();
        assertThat(ExploreVerifier.sentence("게시 시간을 바꿔 본 경험이 SNS 운영에 이어집니다.")).isEmpty();
        assertThat(ExploreVerifier.sentence("당신의 경험이 SNS 운영에 이어져요.")).isEmpty();
        assertThat(ExploreVerifier.sentence("선호 전공이 아니어도 SNS 운영에 이어져요.")).isEmpty();
        assertThat(ExploreVerifier.sentence("짧아요.")).isEmpty();
        assertThat(ExploreVerifier.sentence("**강조**한 문장도 별표를 지우고 써요.")).contains("강조한 문장도 별표를 지우고 써요.");
    }

    @Test
    void 왜_맞나요는_통과한_조각만_남기고_points가_없으면_실패() {
        Student s = new Student(List.of("인스타그램 계정을 1년 동안 운영했어요."), List.of(), null);
        WhyDraft ok = new WhyDraft("계정을 운영한 경험이 이 자리의 SNS 운영과 이어져요.",
                List.of(new PointDraft("계정을 운영한 경험이 SNS 채널 운영에 바로 쓰여요.", "인스타그램 계정을 1년 동안",
                                "자사 SNS 채널 콘텐츠 운영 및 홍보 마케팅 업무 지원"),
                        new PointDraft("지어낸 경험은 빠져야 해요.", "동아리 회장을 맡았어요", "오피스 프로그램 우수자")),
                List.of(new PhraseDraft("경쟁사 제품을 분석해 볼 수 있어요.", "현재 뷰티 트렌드 및 경쟁사 제품 분석"),
                        new PhraseDraft("공고에 없는 일을 해 볼 수 있어요.", "해외 출장 업무")),
                new PhraseDraft("오피스 프로그램을 익혀 두면 좋아요.", "오피스 프로그램 우수자"));
        var why = ExploreVerifier.why(ok, s, DOC).orElseThrow();
        assertThat(why.points()).hasSize(1);
        assertThat(why.tryNew()).hasSize(1);
        assertThat(why.prepare()).isNotNull();
        WhyDraft bad = new WhyDraft("계정을 운영한 경험이 이어져요.",
                List.of(new PointDraft("지어낸 경험은 빠져야 해요.", "동아리 회장을 맡았어요", "오피스 프로그램 우수자")),
                List.of(), null);
        assertThat(ExploreVerifier.why(bad, s, DOC)).isEmpty();
    }
}
