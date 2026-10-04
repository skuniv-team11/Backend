package kr.ac.skuniv.coopradar.recommend;

import static org.assertj.core.api.Assertions.assertThat;

import com.anthropic.models.messages.MessageCreateParams;
import java.util.List;
import kr.ac.skuniv.coopradar.recommend.RecommendDtos.Citation;
import kr.ac.skuniv.coopradar.recommend.RecommendDtos.SourceType;
import kr.ac.skuniv.coopradar.recommend.RecommendRepository.JobFacts;
import org.junit.jupiter.api.Test;

/** 이유 문장 검증 규칙과 구조화 출력 스키마(호출 없이). */
class ReasonUnitTest {

    private static final List<Citation> CITES = List.of(
            new Citation(SourceType.TESTIMONIAL, "2025-2 우수 참여수기", 9, "신제품 기획과 마케팅 업무 전반 보조"),
            new Citation(SourceType.OPERATION_PLAN, "소서 운영계획서", 4, "*시장환경 및 경쟁사 분석"));
    private static final JobFacts FACTS = new JobFacts("소서", "도소매 제조업", "화장품", "국내 마케팅", "마케팅팀",
            "EXPERIENCE", "온라인 채널 운영", "트렌드에 관심", "뷰티 시장 실무 경험", "자사 인스타그램 운영 방식 파악",
            "미용예술대학");
    private static final JobFacts FASHION = new JobFacts("세정", "제조업", "의류", "SNS, 영상", "디지털미디어팀",
            "EXPERIENCE", "SNS 매거진 채널 / 유튜브 채널 콘텐츠 기획 및 제작 서브", "무관", "의류 산업의 이해",
            "브랜드 소개 및 마케팅 업무 소개", "무대패션디자인전공, 광고홍보콘텐츠학과");

    @Test
    void 길이와_근거_번호와_인용을_본다() {
        assertThat(ReasonService.validate(draft("관심 분야와 바로 이어지는 자리예요.", 1L), CITES, FACTS))
                .contains("관심 분야와 바로 이어지는 자리예요.");
        // 공백·줄바꿈은 한 칸으로
        assertThat(ReasonService.validate(draft("관심 분야와\n  이어지는 자리예요."), CITES, FACTS))
                .contains("관심 분야와 이어지는 자리예요.");
        // 너무 짧거나 김
        assertThat(ReasonService.validate(draft("좋아요."), CITES, FACTS)).isEmpty();
        assertThat(ReasonService.validate(draft("가".repeat(201)), CITES, FACTS)).isEmpty();
        // 근거 번호 범위 밖·중복
        assertThat(ReasonService.validate(draft("관심 분야와 이어지는 자리예요.", 3L), CITES, FACTS)).isEmpty();
        assertThat(ReasonService.validate(draft("관심 분야와 이어지는 자리예요.", 1L, 1L), CITES, FACTS)).isEmpty();
        // 인용은 근거·직무 원문에 있어야 한다(공백 무시)
        assertThat(ReasonService.validate(draft("선배가 '신제품 기획과  마케팅 업무'를 맡았어요."), CITES, FACTS)).isPresent();
        assertThat(ReasonService.validate(draft("“온라인 채널 운영”을 배우는 자리예요."), CITES, FACTS)).isPresent();
        assertThat(ReasonService.validate(draft("'업계 최고 연봉'을 주는 자리예요."), CITES, FACTS)).isEmpty();
        assertThat(ReasonService.validate(null, CITES, FACTS)).isEmpty();
        // 문체·호칭: 해요체로 끝나야 하고 '습니다'·'당신'은 안 된다. 별표는 지운다
        assertThat(ReasonService.validate(draft("관심 분야와 이어지는 자리입니다."), CITES, FACTS)).isEmpty();
        assertThat(ReasonService.validate(draft("당신의 관심 분야와 이어지는 자리예요."), CITES, FACTS)).isEmpty();
        assertThat(ReasonService.validate(draft("*온라인 채널 운영*을 배울 수 있어요."), CITES, FACTS))
                .contains("온라인 채널 운영을 배울 수 있어요.");
    }

    @Test
    void 주차_계획_인용도_원문으로_본다() {
        assertThat(ReasonService.validate(draft("'자사 인스타그램 운영 방식 파악'부터 해요."), CITES, FACTS)).isPresent();
    }

    @Test
    void 선호_전공_밖이면_학과를_직무와_잇지_않는다() {
        var outside = kr.ac.skuniv.coopradar.eligibility.EligibilityDtos.MajorMatch.NOT_LISTED;
        var inside = kr.ac.skuniv.coopradar.eligibility.EligibilityDtos.MajorMatch.MATCH;
        String text = "메이크업 디자인 지식을 바탕으로 콘텐츠 제작 실무를 익혀요.";
        assertThat(ReasonService.validate(draft(text), CITES, FASHION, outside, "메이크업디자인학과", null)).isEmpty();
        assertThat(ReasonService.validate(draft(text), CITES, FASHION, inside, "메이크업디자인학과", null)).isPresent();
        assertThat(ReasonService.validate(draft("유튜브 채널 콘텐츠 기획을 거들어요."), CITES, FASHION, outside,
                "메이크업디자인학과", null)).isPresent();
    }

    @Test
    void 관심_낱말을_원문에_없는_기관_업종에_붙이지_않는다() {
        var match = kr.ac.skuniv.coopradar.eligibility.EligibilityDtos.MajorMatch.MATCH;
        String interest = "뷰티 브랜드 SNS 마케팅";
        // 의류 회사를 '뷰티 브랜드'라고 부르면 탈락
        assertThat(ReasonService.validate(draft("SNS 매거진 채널을 운영하며 뷰티 브랜드의 마케팅 전략을 배워요."), CITES, FASHION,
                match, "메이크업디자인학과", interest)).isEmpty();
        // 원문에 '뷰티'가 있으면(화장품 회사) 괜찮다. 관심을 그냥 말하는 것도 괜찮다
        assertThat(ReasonService.validate(draft("뷰티 시장에서 SNS 운영을 거들어요."), CITES, FACTS, match,
                "메이크업디자인학과", interest)).isPresent();
        assertThat(ReasonService.validate(draft("뷰티에 관심이 있다면 SNS 매거진 채널 운영을 거들어요."), CITES, FASHION, match,
                "메이크업디자인학과", interest)).isPresent();
        assertThat(ReasonService.interestWords("뷰티에 관심, SNS·마케팅")).containsExactly("뷰티", "관심", "SNS", "마케팅");
    }

    @Test
    void 구조화_출력_스키마를_호출_없이_만들_수_있다() {
        var params = MessageCreateParams.builder()
                .model("claude-haiku-4-5-20251001")
                .maxTokens(400)
                .system("s")
                .addUserMessage("u")
                .outputConfig(ReasonDraft.class)
                .build();
        assertThat(params.rawParams().outputConfig()).isPresent();
    }

    private static ReasonDraft draft(String text, Long... numbers) {
        return new ReasonDraft(text, List.of(numbers));
    }
}
