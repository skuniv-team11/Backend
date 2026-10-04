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
 * 기본 이유 문장과 근거 인용은 {@link ReasonTemplates}·{@link EvidencePicker}가 만든다(ADR-0020).
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
    public record Scored(Judged judged, Features features, double score, double interest, boolean majorFit, Fit fit) {

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
            Fit fit = majorFit && (!hasInterest || interestClose(sims[i])) ? Fit.HIGH : Fit.MEDIUM;
            out.add(new Scored(j, f, score, sims[i], majorFit, fit));
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

    /** 관심 유사도가 높은가(적합도 HIGH와 기본 이유 문장의 '관심 분야' 기준). 관심 문장이 없으면 0이라 false. */
    static boolean interestClose(double interest) {
        return interest > 0 && interest >= HIGH_INTEREST;
    }
}
