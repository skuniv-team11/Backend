package kr.ac.skuniv.coopradar.recommend;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import kr.ac.skuniv.coopradar.eligibility.EligibilityDtos.MajorMatch;
import kr.ac.skuniv.coopradar.eligibility.JobRequirement;
import kr.ac.skuniv.coopradar.job.InstitutionRef;
import kr.ac.skuniv.coopradar.job.JobDetail.Closing;
import kr.ac.skuniv.coopradar.recommend.EvidencePicker.Picked;
import kr.ac.skuniv.coopradar.recommend.EvidencePicker.Testimonial;
import kr.ac.skuniv.coopradar.recommend.EvidenceText.JobText;
import kr.ac.skuniv.coopradar.recommend.EvidenceText.Kind;
import kr.ac.skuniv.coopradar.recommend.EvidenceText.Segment;
import kr.ac.skuniv.coopradar.recommend.RecommendDtos.Citation;
import kr.ac.skuniv.coopradar.recommend.RecommendDtos.SourceType;
import org.junit.jupiter.api.Test;

/** 근거 조각·인용 고르기·기본 이유 문장(ADR-0020). DB 없이 2026-2 시드의 소서 원문으로 본다. */
class EvidenceTest {

    private static final String OVERVIEW_122 = """
            *시장환경 및 경쟁사 분석을 통한 상품 기획 아이디어 제안
            *현재 트렌드에 맞는 자사 신제품 상품 기획
            *브랜드 운영에 필요한 콘텐츠 제작 및 레퍼런스 서치
            *자사 SNS 채널 콘텐츠 운영 및 홍보 마케팅 업무 지원""";
    private static final String COMP_122 = "오피스 프로그램 우수자, 평소 뷰티에 관심이 많은 자, 뷰티 SNS를 운영해 본 경험이 있는 자";
    private static final String COMP_123 = "영어 가능자, " + COMP_122;
    private static final List<String> WEEKLY_122 = List.of("회사 소개 및 기본 업무 교육 실시",
            "자사 인스타그램 운영 방식 파악/현재 뷰티 트렌드 및 경쟁사 제품 분석",
            "본격적인 SNS 운영 지원/제품 개발 회의 참여 및 리서치");

    private static final JobText J122 = new JobText(122, 7, "마케팅팀", "국내 마케팅", OVERVIEW_122, COMP_122,
            "현직자와의 Co-work을 통해 뷰티 시장에서의 제품 기획 및 국내 마케팅 실무 기회 부여", WEEKLY_122, 4, 4, 4, 4,
            "소서 운영계획서");
    private static final JobText J120 = new JobText(120, 7, "세일즈팀", "AMD",
            "*플랫폼 운영 및 커뮤니케이션\n*프로모션 및 마케팅 지원", "오피스 프로그램 우수자, 온라인MD업무에 관심이 있는 자",
            "현직자와의 Co-work을 통해 뷰티 시장에서의 AMD 실무 기회 부여", List.of("온보딩 및 플랫폼 이해"), 2, 2, 2, 2,
            "소서 운영계획서");
    private static final List<Testimonial> SOSEO = List.of(
            new Testimonial(7, "2025-2 우수 참여수기", "2025-2", "마케팅팀", 9,
                    List.of("신제품 기획과 마케팅 업무 전반 보조", "콘텐츠 기획 보조")),
            new Testimonial(7, "2025-1 우수 참여수기", "2025-1", "물류팀", 9, List.of("인터넷 판매건 출고시키기")),
            new Testimonial(7, "2024-2 우수 참여수기", "2024-2", "마케팅팀", 15,
                    List.of("서포터즈 관리 및 선발", "SNS 계정 관리 및 업로드")));

    @Test
    void 원문을_글머리표만_떼고_조각으로_나눈다() {
        List<Segment> s = EvidenceText.segments(J122);
        assertThat(s).filteredOn(x -> x.kind() == Kind.OVERVIEW).extracting(Segment::text)
                .containsExactly("시장환경 및 경쟁사 분석을 통한 상품 기획 아이디어 제안", "현재 트렌드에 맞는 자사 신제품 상품 기획",
                        "브랜드 운영에 필요한 콘텐츠 제작 및 레퍼런스 서치", "자사 SNS 채널 콘텐츠 운영 및 홍보 마케팅 업무 지원");
        assertThat(s).filteredOn(x -> x.kind() == Kind.COMPETENCY).extracting(Segment::text)
                .containsExactly("오피스 프로그램 우수자", "평소 뷰티에 관심이 많은 자", "뷰티 SNS를 운영해 본 경험이 있는 자");
        // 주차 계획은 '/'로 나누고, 직무 개요와 전공 요건이 같은 쪽일 때만 그 쪽으로
        assertThat(s).filteredOn(x -> x.kind() == Kind.WEEKLY).extracting(Segment::text)
                .contains("자사 인스타그램 운영 방식 파악", "본격적인 SNS 운영 지원");
        assertThat(s).allMatch(x -> x.page() == 4);
        JobText splitPages = new JobText(1, 1, "팀", "직무", "개요 한 줄입니다", null, null, WEEKLY_122, 2, null, null, 3, "계획서");
        assertThat(EvidenceText.segments(splitPages)).extracting(Segment::kind).containsOnly(Kind.OVERVIEW);
    }

    @Test
    void 짧은_조각은_이웃과_붙이고_괄호_안_쉼표와_머리말은_나누지_않는다() {
        JobText j = new JobText(1, 1, "팀", "직무", "[AE] - 브랜드 소셜미디어 채널 기획",
                "오피스 프로그램(Excel, PowerPoint) 활용 가능한 분 / 지식/기술 역량 : SNS 운영", null,
                List.of("온/오프라인 데이터 취합 및 정리"), 2, 2, null, 2, "계획서");
        List<Segment> s = EvidenceText.segments(j);
        assertThat(s).extracting(Segment::text).containsExactly("브랜드 소셜미디어 채널 기획",
                "오피스 프로그램(Excel, PowerPoint) 활용 가능한 분", "SNS 운영", "온/오프라인 데이터 취합 및 정리");
        // 줄 전체가 [팀명(인원)-설명]이면 설명만, ' - '로 이어 쓴 항목은 나눈다, '무관'·'제한 없음'은 요건이 아니다
        JobText bracket = new JobText(2, 1, "팀", "직무", "[마케팅팀(1)-영상콘텐츠 기획/관리 및 촬영/편집/운영 등 제반 업무]",
                "- 숏폼 콘텐츠 기획 경험자 - AI 툴 활용 경험자 / 외국어 역량 : 제한 없음", null, List.of(), 3, 3, null, 3, "계획서");
        assertThat(EvidenceText.segments(bracket)).extracting(Segment::text).containsExactly(
                "영상콘텐츠 기획/관리 및 촬영/편집/운영 등 제반 업무", "숏폼 콘텐츠 기획 경험자", "AI 툴 활용 경험자");
        assertThat(EvidenceText.competencyItems("무관")).isEmpty();
    }

    @Test
    void 수기_팀은_띄어쓰기_숫자_괄호_끝의_팀_부_실을_떼고_비교한다() {
        assertThat(EvidenceText.sameTeam("마케팅솔루션 2팀", "마케팅솔루션팀", "인턴")).isTrue();
        assertThat(EvidenceText.sameTeam("OL디자인실", "OL디자인/VvOL디자인팀", "의상디자인")).isTrue();
        assertThat(EvidenceText.sameTeam("강의제작팀", "기획본부", "강의제작")).isTrue();
        assertThat(EvidenceText.sameTeam("마케팅&제품개발팀", "마케팅팀", "국내 마케팅")).isTrue();
        assertThat(EvidenceText.sameTeam("마케팅팀", "세일즈팀", "AMD")).isFalse();
        assertThat(EvidenceText.sameTeam("OL디자인실", "니트디자인팀", "의상디자인")).isFalse();
        assertThat(EvidenceText.sameTeam("사업부", "프로젝트 랩(기술무역)", "디지털 기술무역")).isFalse();
    }

    @Test
    void 관심과_가장_많이_겹치는_계획서_조각과_같은_팀_수기_한_줄을_고른다() {
        Picked p = picker("뷰티 브랜드 SNS 마케팅").pick(J122, SOSEO);
        assertThat(p.citations()).extracting(Citation::sourceType)
                .containsExactly(SourceType.TESTIMONIAL, SourceType.OPERATION_PLAN);
        assertThat(p.citations().get(0).quote()).isNotEqualTo("인터넷 판매건 출고시키기"); // 물류팀 수기는 쓰지 않는다
        assertThat(p.matched()).isNotNull();
        assertThat(p.matched().text()).contains("SNS");
        assertThat(p.citations().get(1).quote()).isEqualTo(p.matched().text()).doesNotStartWith("*");
        assertThat(p.citations().get(1).page()).isEqualTo(4);
    }

    @Test
    void 수기는_최근_것만이_아니라_같은_팀_수기_전부에서_고른다() {
        Picked p = picker("SNS 계정 운영").pick(J122, SOSEO);
        assertThat(p.citations().get(0)).isEqualTo(
                new Citation(SourceType.TESTIMONIAL, "2024-2 우수 참여수기", 15, "SNS 계정 관리 및 업로드"));
    }

    @Test
    void 관심이_없거나_겹치지_않으면_직무_개요_첫_조각과_최근_같은_팀_수기_첫_줄() {
        for (String interest : new String[] {null, "건축 구조 설계"}) {
            Picked p = picker(interest).pick(J122, SOSEO);
            assertThat(p.matched()).isNull();
            assertThat(p.citations()).extracting(Citation::quote)
                    .containsExactly("신제품 기획과 마케팅 업무 전반 보조", "시장환경 및 경쟁사 분석을 통한 상품 기획 아이디어 제안");
        }
    }

    @Test
    void 같은_팀_수기가_없으면_수기는_붙이지_않는다() {
        Picked p = picker("뷰티 브랜드 SNS 마케팅").pick(J120, SOSEO);
        assertThat(p.citations()).extracting(Citation::sourceType).containsExactly(SourceType.OPERATION_PLAN);
    }

    @Test
    void 기본_문장은_겹친_원문과_선호_전공_표기를_따옴표로_짚는다() {
        Segment comp = new Segment(Kind.COMPETENCY, "뷰티 SNS를 운영해 본 경험이 있는 자", 4);
        assertThat(ReasonTemplates.build(MajorMatch.MATCH, "미용예술대학", true, comp, false, true, null))
                .isEqualTo("관심 분야가 요구 역량 '뷰티 SNS를 운영해 본 경험이 있는 자'와 겹치고, "
                        + "선호 전공 '미용예술대학'에 소속 학과가 들어 있어요.");
        Segment overview = new Segment(Kind.OVERVIEW, "자사 SNS 채널 콘텐츠 운영 및 홍보 마케팅 업무 지원", 4);
        assertThat(ReasonTemplates.build(MajorMatch.MATCH, "미용예술대학", true, overview, false, true, null))
                .startsWith("관심 분야가 직무 개요 '자사 SNS 채널 콘텐츠 운영 및 홍보 마케팅 업무 지원'과 겹치고");
        // 관심이 가깝지 않으면(적합도 기준) 관심 문구를 넣지 않는다
        assertThat(ReasonTemplates.build(MajorMatch.MATCH, null, false, overview, true, true, null))
                .isEqualTo("선호 전공에 소속 학과가 들어 있고, 채용연계형이에요.");
        // 선호 전공 밖이면 그 사실을 꼭 붙인다
        assertThat(ReasonTemplates.build(MajorMatch.NOT_LISTED, null, true, null, false, true, null))
                .isEqualTo("관심 분야와 직무 내용이 가까워요. " + ReasonTemplates.MAJOR_NOT_LISTED);
        assertThat(ReasonTemplates.build(MajorMatch.OPEN, null, false, null, false, false, "같은 팀의 A와 달리 'B' 요건이 있어요."))
                .isEqualTo("전공 무관 자리예요. 같은 팀의 A와 달리 'B' 요건이 있어요.");
        assertThat(ReasonTemplates.build(MajorMatch.NOT_LISTED, null, false, null, false, true, null))
                .isEqualTo("지원 조건을 모두 통과한 자리예요. " + ReasonTemplates.MAJOR_NOT_LISTED);
    }

    @Test
    void 같은_팀_다른_직무와_요건이_다른_점을_한_문장으로() {
        JobRequirement domestic = requirement(122, "국내 마케팅", "Y3_4", null);
        JobRequirement overseas = requirement(123, "해외 마케팅", "Y3_4", null);
        assertThat(ReasonTemplates.contrast(domestic, COMP_122, overseas, COMP_123))
                .isEqualTo("같은 팀의 해외 마케팅과 달리 '영어 가능자' 요건은 없어요.");
        assertThat(ReasonTemplates.contrast(overseas, COMP_123, domestic, COMP_122))
                .isEqualTo("같은 팀의 국내 마케팅과 달리 '영어 가능자' 요건이 있어요.");
        JobRequirement senior = requirement(9, "디자인", "Y4", new BigDecimal("3.0"));
        assertThat(ReasonTemplates.contrast(senior, COMP_122, domestic, COMP_122))
                .isEqualTo("같은 팀의 국내 마케팅과 달리 '4학년' · '학점 3.0 이상' 요건이 있어요.");
        assertThat(ReasonTemplates.contrast(domestic, COMP_122, domestic, COMP_122)).isNull();
        // 차이가 세 개 이상이면 하는 일이 다른 직무로 보고 말하지 않는다
        assertThat(ReasonTemplates.contrast(senior, COMP_123, domestic, COMP_122)).isNull();
    }

    @Test
    void 받침에_따라_와_과() {
        assertThat(ReasonTemplates.withOrAnd("해외 마케팅")).isEqualTo("과");
        assertThat(ReasonTemplates.withOrAnd("있는 자")).isEqualTo("와");
        assertThat(ReasonTemplates.withOrAnd("AMD")).isEqualTo("와");
    }

    private static EvidencePicker picker(String interest) {
        return new EvidencePicker(List.of(J122, J120), SOSEO, interest);
    }

    private static JobRequirement requirement(int id, String title, String gradeRule, BigDecimal gpa) {
        return new JobRequirement(id, id, title, "마케팅팀", new InstitutionRef(7, "소서"), "SEMESTER", gradeRule, gpa, "NONE",
                "NONE", null, null, null, "미용예술대학", false, Set.of(43), List.of(), new Closing(null, null, false), 0);
    }
}
