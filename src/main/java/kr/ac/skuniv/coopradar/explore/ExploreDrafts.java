package kr.ac.skuniv.coopradar.explore;

import java.util.List;

/**
 * AI가 구조화 출력으로 돌려주는 초안(ADR-0003·0031·0038). 그대로 내보내지 않고 {@link ExploreVerifier}가 원문과 대조한다.
 * 필드 이름은 프롬프트(prompts/explore-*-system.md)에 적은 이름과 같다.
 */
public final class ExploreDrafts {

    private ExploreDrafts() {
    }

    /** AI가 붙이는 짝의 종류(구조화 출력이라 한글 상수, ADR-0003). */
    public enum LinkKind { 같은일, 쓰이는능력, 하고싶은일 }

    /** 탐색: 학생 글과 이어지는 자리와 그 짝. 순서는 서버가 정한다(ADR-0038). */
    public record RankDraft(List<LinkedJob> jobs) {
    }

    /**
     * @param id     자리 번호(직무 id)
     * @param links  이 자리 업무와 학생 글의 짝 1~5개
     * @param reason 해요체 한 문장
     */
    public record LinkedJob(long id, List<LinkDraft> links, String reason) {
    }

    /**
     * @param kind         같은일 · 쓰이는능력(학생 구절은 해 본 일에서) · 하고싶은일(고른 카드나 관심 분야에서)
     * @param studentQuote 학생 글 한 줄 안의 구절
     * @param jobQuote     그 자리 설명 한 칸 안의 구절
     */
    public record LinkDraft(LinkKind kind, String studentQuote, String jobQuote) {
    }

    /** '왜 맞나요'. */
    public record WhyDraft(String summary, List<PointDraft> points, List<PhraseDraft> tryNew, PhraseDraft prepare) {
    }

    public record PointDraft(String text, String studentQuote, String jobQuote) {
    }

    public record PhraseDraft(String text, String jobQuote) {
    }
}
