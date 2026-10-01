package kr.ac.skuniv.coopradar.recommend;

import java.util.List;
import kr.ac.skuniv.coopradar.eligibility.EligibilityDtos.Verdict;
import kr.ac.skuniv.coopradar.job.InstitutionRef;
import kr.ac.skuniv.coopradar.job.RoundRef;
import kr.ac.skuniv.coopradar.job.Stipend;

/** 추천 응답 모양(docs/api #15·#16: recommendations.json, recommendation-reason.json). 필드 이름이 JSON 이름이다. */
public final class RecommendDtos {

    private RecommendDtos() {
    }

    /** 적합도 등급. 점수는 응답에 넣지 않는다(정렬에만 쓴다). */
    public enum Fit { HIGH, MEDIUM }

    public enum SourceType { OPERATION_PLAN, TESTIMONIAL }

    /** 근거 인용. 원문 PDF 링크는 주지 않는다. */
    public record Citation(SourceType sourceType, String documentTitle, int page, String quote) {
    }

    /** @param reasonStatus PENDING이면 프론트가 카드마다 #16(이유 문장)을 부른다 */
    public record Recommendation(int rank, int jobId, String title, InstitutionRef institution, Verdict verdict,
                                 Fit fit, String jobType, Stipend stipend, String reasonTemplate,
                                 String reasonStatus, List<Citation> citations) {
    }

    /** 추천이 0개일 때 막은 요건별 직무 수. */
    public record Blocked(String item, int count) {
    }

    public record Recommendations(RoundRef round, List<Recommendation> items, List<Blocked> blockedBy) {
    }

    /** 이유 문장의 출처. LLM 실패·5초 초과·호출 제한이어도 200 + TEMPLATE. */
    public enum ReasonSource { LLM, CACHE, TEMPLATE }

    public record RecommendationReason(int jobId, ReasonSource source, String text, List<Citation> citations) {
    }
}
