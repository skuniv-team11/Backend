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
 * <p>
 * 추천할 이유가 있는 직무(ADR-0022): 선호 전공이 맞거나(MATCH·OPEN), 관심 문장과 겹친다 — 원 코사인이 0.02 이상이면서
 * 0.05 이상이거나 가장 많이 겹친 직무의 절반 이상. 둘 다 아니면 점수가 있어도 추천하지 않는다(5개보다 적을 수 있다).
 * 기본 이유 문장과 근거 인용은 {@link ReasonTemplates}·{@link EvidencePicker}가 만든다(ADR-0020).
 */
public final class FitScorer {

    static final double W_MAJOR = 5;
    static final double W_INTEREST = 2;
    static final double W_ELIGIBLE = 1;
    static final double W_STIPEND = 0.5;
    static final double W_HIRING = 0.5;
    public static final double HIGH_INTEREST = 0.5;
    /** 관심 문장과 겹친다고 볼 원 코사인 하한(이보다 낮으면 '프로젝트'·'개발' 같은 흔한 낱말 하나가 겹친 정도다). */
    static final double INTEREST_FLOOR = 0.02;
    /** 이 이상이면 가장 많이 겹친 직무와 상관없이 겹친다고 본다. */
    static final double INTEREST_CLEAR = 0.05;

    private FitScorer() {
    }

    /** 직무 하나의 점수 재료. text는 키워드 유사도에 쓰는 직무 텍스트다. */
    public record Features(String text, String jobType, Stipend stipend) {
    }

    /**
     * @param interest      관심 키워드 유사도(0~1, 후보 중 최댓값 기준). 관심 문장이 없으면 0
     * @param interestMatch 관심 문장과 겹친다(원 코사인 기준, {@link #interestMatch}). 관심 문장이 없으면 false
     */
    public record Scored(Judged judged, Features features, double score, double interest, boolean majorFit,
                         boolean interestMatch, Fit fit) {

        public int jobId() {
            return judged.requirement().jobId();
        }

        /** 추천할 이유가 있는가: 선호 전공이 맞거나 관심 문장과 겹친다(ADR-0022). */
        public boolean relevant() {
            return majorFit || interestMatch;
        }
    }

    /** 후보(INELIGIBLE이 아닌 직무)를 점수 순으로. */
    public static List<Scored> score(List<Judged> candidates, Map<Integer, Features> features, String interestText) {
        boolean hasInterest = interestText != null && !interestText.isBlank();
        double[] sims = new double[candidates.size()];
        double[] raw = new double[candidates.size()];
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
                raw[i] = KeywordSimilarity.cosine(query, model.vector(corpus.get(i)));
                sims[i] = raw[i];
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
            out.add(new Scored(j, f, score, sims[i], majorFit, hasInterest && interestMatch(raw[i], sims[i]), fit));
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
     * 관심 문장과 겹치는가(ADR-0022). 원 코사인이 하한(0.02) 이상이고, 0.05 이상이거나 후보 중 가장 많이 겹친 직무의 절반 이상.
     * 상대 기준만 쓰면 아무것도 안 겹쳐도 1등은 늘 통과하고, 절대 기준만 쓰면 '백엔드 개발자'처럼 낱말이 많은 관심 문장이
     * 딱 맞는 직무(소프트웨어 개발, 원 코사인 0.04)도 떨어뜨려서 둘을 같이 쓴다.
     *
     * @param raw        원 코사인
     * @param normalized 후보 중 최댓값으로 나눈 값
     */
    static boolean interestMatch(double raw, double normalized) {
        return raw >= INTEREST_FLOOR && (raw >= INTEREST_CLEAR || normalized >= HIGH_INTEREST);
    }

    /** 관심 유사도가 높은가(적합도 HIGH와 기본 이유 문장의 '관심 분야' 기준). 관심 문장이 없으면 0이라 false. */
    static boolean interestClose(double interest) {
        return interest > 0 && interest >= HIGH_INTEREST;
    }
}
