package kr.ac.skuniv.coopradar.recommend;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import kr.ac.skuniv.coopradar.eligibility.EligibilityDtos.MajorMatch;
import kr.ac.skuniv.coopradar.eligibility.EligibilityDtos.Verdict;
import kr.ac.skuniv.coopradar.eligibility.EligibilityService.Judged;
import kr.ac.skuniv.coopradar.job.Stipend;
import kr.ac.skuniv.coopradar.recommend.RecommendDtos.Fit;

/**
 * 적합도 점수(ADR-0018). 규칙 + 키워드이고 임베딩은 쓰지 않는다(E5: 임베딩이 선호 전공 규칙보다 낫지 않았다).
 * <pre>
 * 점수 = 5 × 선호 전공 일치(MATCH·OPEN)
 *      + 2 × 관심 키워드 유사도(후보 중 최댓값으로 나눈 0~1, 관심 문장이 없으면 0)
 *      + 1 × 지원 가능(ELIGIBLE)
 *      + 0.5 × 지원비(최저임금 대비 75% → 0, 100% 이상 → 1)
 *      + 0.5 × 채용연계형
 * </pre>
 * 선호 전공 가중치(5)가 나머지 합(4)보다 커서 전공이 맞는 직무가 늘 먼저다. 같은 점수면 리스트 순번.
 * 등급: 선호 전공이 맞고, 관심 문장을 안 적었거나 관심 유사도가 0.5 이상이면 HIGH, 아니면 MEDIUM.
 */
public final class FitScorer {

    static final double W_MAJOR = 5;
    static final double W_INTEREST = 2;
    static final double W_ELIGIBLE = 1;
    static final double W_STIPEND = 0.5;
    static final double W_HIRING = 0.5;
    public static final double HIGH_INTEREST = 0.5;

    private FitScorer() {
    }

    /** 직무 하나의 점수 재료. text는 키워드 유사도에 쓰는 직무 텍스트다. */
    public record Features(String text, String jobType, Stipend stipend) {
    }

    /**
     * @param interest 관심 키워드 유사도(0~1, 후보 중 최댓값 기준). 관심 문장이 없으면 0
     */
    public record Scored(Judged judged, Features features, double score, double interest, boolean majorFit, Fit fit,
                         String reasonTemplate) {

        public int jobId() {
            return judged.requirement().jobId();
        }
    }

    /** 후보(INELIGIBLE이 아닌 직무)를 점수 순으로. */
    public static List<Scored> score(List<Judged> candidates, Map<Integer, Features> features, String interestText) {
        boolean hasInterest = interestText != null && !interestText.isBlank();
        double[] sims = new double[candidates.size()];
        if (hasInterest) {
            List<String> corpus = new ArrayList<>();
            for (Judged j : candidates) {
                corpus.add(features.get(j.requirement().jobId()).text());
            }
            corpus.add(interestText);
            KeywordSimilarity model = new KeywordSimilarity(corpus);
            var query = model.vector(interestText);
            double max = 0;
            for (int i = 0; i < candidates.size(); i++) {
                sims[i] = KeywordSimilarity.cosine(query, model.vector(corpus.get(i)));
                max = Math.max(max, sims[i]);
            }
            for (int i = 0; i < sims.length; i++) {
                sims[i] = max > 0 ? sims[i] / max : 0;
            }
        }
        List<Scored> out = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            Judged j = candidates.get(i);
            Features f = features.get(j.requirement().jobId());
            MajorMatch major = j.result().majorMatch();
            boolean majorFit = major == MajorMatch.MATCH || major == MajorMatch.OPEN;
            boolean eligible = j.result().verdict() == Verdict.ELIGIBLE;
            boolean hiring = "HIRING".equals(f.jobType());
            double score = (majorFit ? W_MAJOR : 0) + W_INTEREST * sims[i] + (eligible ? W_ELIGIBLE : 0)
                    + W_STIPEND * stipendScore(f.stipend()) + (hiring ? W_HIRING : 0);
            boolean interestClose = hasInterest && sims[i] >= HIGH_INTEREST;
            Fit fit = majorFit && (!hasInterest || interestClose) ? Fit.HIGH : Fit.MEDIUM;
            out.add(new Scored(j, f, score, sims[i], majorFit, fit,
                    template(major, interestClose, hiring, eligible)));
        }
        out.sort(Comparator.comparingDouble(Scored::score).reversed()
                .thenComparingInt(s -> s.judged().requirement().listSeq()));
        return out;
    }

    /** 최저임금 대비 75%(법정 하한) → 0, 100% 이상 → 1, 사이는 비례. 금액·기준이 없으면 0. */
    static double stipendScore(Stipend stipend) {
        BigDecimal ratio = stipend == null ? null : stipend.minWageRatio();
        if (ratio == null) {
            return 0;
        }
        double r = (ratio.doubleValue() - 75) / 25;
        return Math.max(0, Math.min(1, r));
    }

    /**
     * 바로 보여 줄 기본 이유 문장(규칙). 해당하는 이유를 앞에서 두 개까지 이어 붙인다.
     * #16 이유 문장이 실패해도 이 문장이 남는다.
     */
    static String template(MajorMatch major, boolean interestClose, boolean hiring, boolean eligible) {
        List<String[]> clauses = new ArrayList<>(); // {이어지는 꼴, 끝맺는 꼴}
        if (interestClose) {
            clauses.add(new String[] {"관심 분야와 직무 내용이 가깝고", "관심 분야와 직무 내용이 가까워요"});
        }
        if (major == MajorMatch.MATCH) {
            clauses.add(new String[] {"선호 전공에 소속 학과가 들어 있고", "선호 전공에 소속 학과가 들어 있어요"});
        } else if (major == MajorMatch.OPEN) {
            clauses.add(new String[] {"전공 무관 자리이고", "전공 무관 자리예요"});
        }
        if (hiring) {
            clauses.add(new String[] {"채용연계형이고", "채용연계형이에요"});
        }
        if (clauses.isEmpty()) {
            return eligible ? "지원 조건을 모두 통과한 자리예요." : "확인할 조건만 챙기면 지원할 수 있는 자리예요.";
        }
        if (clauses.size() == 1) {
            return clauses.getFirst()[1] + ".";
        }
        return clauses.get(0)[0] + ", " + clauses.get(1)[1] + ".";
    }
}
