package kr.ac.skuniv.coopradar.recommend;

import java.util.List;
import kr.ac.skuniv.coopradar.eligibility.EligibilityDtos.Verdict;
import kr.ac.skuniv.coopradar.job.InstitutionRef;
import kr.ac.skuniv.coopradar.job.RoundRef;
import kr.ac.skuniv.coopradar.job.Stipend;

/**
 * 규칙 적합도 추천의 값. API로 따로 내보내지 않고(#15·#16은 ADR-0036에서 지움), 직무 탐색이 AI 대신 규칙 추천으로 갈 때
 * (#29 source RULE)와 판정 목록 순서(#14)에 쓴다.
 */
public final class RecommendDtos {

    private RecommendDtos() {
    }

    /** 적합도 등급. 점수는 응답에 넣지 않는다(정렬에만 쓴다). */
    public enum Fit { HIGH, MEDIUM }

    public enum SourceType { OPERATION_PLAN, TESTIMONIAL }

    /** 근거 인용. 원문 PDF 링크는 주지 않는다. */
    public record Citation(SourceType sourceType, String documentTitle, int page, String quote) {
    }

    /** @param reasonTemplate 규칙 문장(학년·평점·학과가 들어 있어 저장하지 않는다 — ADR-0034) */
    public record Recommendation(int rank, int jobId, String title, InstitutionRef institution, Verdict verdict,
                                 Fit fit, String jobType, Stipend stipend, String reasonTemplate,
                                 List<Citation> citations) {
    }

    /** 추천이 0개일 때 막은 요건별 직무 수. */
    public record Blocked(String item, int count) {
    }

    public record Recommendations(RoundRef round, List<Recommendation> items, List<Blocked> blockedBy) {
    }
}
