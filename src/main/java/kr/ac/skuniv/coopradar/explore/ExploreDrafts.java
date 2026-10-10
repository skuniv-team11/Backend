package kr.ac.skuniv.coopradar.explore;

import java.util.List;

/**
 * AI가 구조화 출력으로 돌려주는 초안(ADR-0003·0031). 그대로 내보내지 않고 {@link ExploreVerifier}가 원문과 대조한다.
 * 필드 이름은 프롬프트(prompts/explore-*-system.md)에 적은 이름과 같다.
 */
public final class ExploreDrafts {

    private ExploreDrafts() {
    }

    /** 탐색 순서: 적합한 순서로 5개까지. */
    public record RankDraft(List<RankedJob> ranking) {
    }

    /**
     * @param id           자리 번호(직무 id)
     * @param studentQuote 학생 글 한 줄 안의 구절
     * @param jobQuote     그 자리 설명 한 칸 안의 구절
     * @param reason       해요체 한 문장
     */
    public record RankedJob(long id, String studentQuote, String jobQuote, String reason) {
    }

    /** '왜 맞나요'. */
    public record WhyDraft(String summary, List<PointDraft> points, List<PhraseDraft> tryNew, PhraseDraft prepare) {
    }

    public record PointDraft(String text, String studentQuote, String jobQuote) {
    }

    public record PhraseDraft(String text, String jobQuote) {
    }
}
