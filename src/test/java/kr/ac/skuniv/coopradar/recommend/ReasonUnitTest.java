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
    private static final JobFacts FACTS = new JobFacts("소서", "국내 마케팅", "마케팅팀", "EXPERIENCE",
            "온라인 채널 운영", "트렌드에 관심", "실무 경험", "미용예술대학");

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
