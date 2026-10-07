package kr.ac.skuniv.coopradar.recommend;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kr.ac.skuniv.coopradar.eligibility.EligibilityDtos.MajorMatch;
import kr.ac.skuniv.coopradar.eligibility.EligibilityDtos.Verdict;
import kr.ac.skuniv.coopradar.eligibility.EligibilityRules;
import kr.ac.skuniv.coopradar.eligibility.EligibilityService.Judged;
import kr.ac.skuniv.coopradar.job.Stipend;
import kr.ac.skuniv.coopradar.recommend.RecommendDtos.Fit;

/**
 * 적합도(ADR-0026, ADR-0018·0022를 고침). 규칙 + 키워드이고 임베딩은 쓰지 않는다(E5).
 * <p>
 * 추천 근거는 둘이다.
 * <ul>
 *   <li><b>전공</b>: 회사가 내 학과를 콕 집어 적었나({@link MajorTier#DIRECT} — 그 표기가 가리키는 학과가 2개 이하),
 *       내 학과가 든 계열·단과대로 적었나({@link MajorTier#GROUP} — 3개 이상), 선호 전공 학과와 같은 묶음의 가까운 학과인가
 *       ({@link MajorTier#NEAR}, ADR-0028), 전공을 따지지 않나({@link MajorTier#OPEN}), 먼 전공인가({@link MajorTier#NONE})</li>
 *   <li><b>관심</b>: 관심 문장과 직무 텍스트의 키워드 유사도. 겹침({@link #interestMatch} — 원 코사인 0.02 이상이면서 0.05 이상이거나
 *       가장 많이 겹친 직무의 절반 이상), 많이 겹침(겹치면서 최댓값의 절반 이상)</li>
 * </ul>
 * <pre>
 * 점수 = 전공(학과 지명 3 · 계열 2 · 가까운 전공 1.5 · 전공 무관 1 · 먼 전공 0)
 *      + 3 × 관심 유사도(최댓값으로 나눈 0~1, 겹칠 때만)
 *      + 1 × 지원 가능(ELIGIBLE — 확인 필요는 포트폴리오를 준비해야 한다)
 *      + 0.5 × 지원비(최저임금 대비 75% → 0, 100% 이상 → 1) + 0.5 × 채용연계형
 * </pre>
 * <ul>
 *   <li><b>추천할 이유</b>: 전공이 학과 지명·계열·가까운 전공이거나, 관심 문장과 겹친다. 전공 무관은 관심 문장이 없을 때만 이유다 —
 *       그때는 전공밖에 볼 것이 없고 '전공 때문에 밀리지 않는 자리'라는 뜻이 된다. 관심 문장을 적었는데 겹치지 않으면, 누구나
 *       지원할 수 있다는 것만으로는 나에게 맞는다는 뜻이 아니라서 뺀다. 전공 무관은 점수가 낮아(1) 학과 지명·계열 뒤에 온다</li>
 *   <li><b>등급</b>: 맞는 근거(학과 지명·계열, 관심 많이 겹침)가 하나 이상이고 어긋나는 근거(먼 전공, 관심을 적었는데 많이
 *       겹치지 않음)가 없으면 HIGH, 아니면 MEDIUM. 가까운 전공·전공 무관과 관심을 안 적은 것은 어느 쪽도 아니다 — 가까운
 *       전공은 관심이 많이 겹칠 때만 HIGH(회사가 직접 적은 전공은 아니라서)</li>
 *   <li><b>순서</b>: HIGH 먼저, 그 안에서 점수, 같으면 리스트 순번</li>
 * </ul>
 * 기본 이유 문장과 근거 인용은 {@link ReasonTemplates}·{@link EvidencePicker}가 만든다(ADR-0020).
 */
public final class FitScorer {

    static final double W_DIRECT = 3;
    static final double W_GROUP = 2;
    static final double W_NEAR = 1.5;
    static final double W_OPEN = 1;
    static final double W_INTEREST = 3;
    static final double W_ELIGIBLE = 1;
    static final double W_STIPEND = 0.5;
    static final double W_HIRING = 0.5;
    public static final double HIGH_INTEREST = 0.5;
    /** 선호 전공 표기가 가리키는 학과가 이 수 이하면 '학과 지명'(예: 광고홍보콘텐츠학과 → 새·옛 이름 2개), 넘으면 계열·단과대. */
    public static final int DIRECT_MAX_DEPARTMENTS = EligibilityRules.NAMED_MAX_DEPARTMENTS;
    /** 관심 문장과 겹친다고 볼 원 코사인 하한(이보다 낮으면 '프로젝트'·'개발' 같은 흔한 낱말 하나가 겹친 정도다). */
    static final double INTEREST_FLOOR = 0.02;
    /** 이 이상이면 가장 많이 겹친 직무와 상관없이 겹친다고 본다. */
    static final double INTEREST_CLEAR = 0.05;

    private FitScorer() {
    }

    /** 전공 근거의 세기. */
    public enum MajorTier { DIRECT, GROUP, NEAR, OPEN, NONE }

    /** 관심 근거: 관심 문장이 없음 · 겹치지 않음 · 조금 겹침 · 많이 겹침. */
    public enum Interest { NOT_GIVEN, NONE, SOME, CLOSE }

    /**
     * 직무 하나의 점수 재료. text는 키워드 유사도에 쓰는 직무 텍스트, directDepartments는 학과를 콕 집은 표기
     * (가리키는 학과가 {@link #DIRECT_MAX_DEPARTMENTS}개 이하)에 든 학과.
     */
    public record Features(String text, String jobType, Stipend stipend, Set<Integer> directDepartments) {
    }

    /**
     * @param interest 관심 키워드 유사도(0~1, 회차 직무 중 최댓값 기준). 관심 문장이 없으면 0
     */
    public record Scored(Judged judged, Features features, double score, double interest, MajorTier major,
                         Interest interestFit, Fit fit) {

        public int jobId() {
            return judged.requirement().jobId();
        }

        /**
         * 추천할 이유가 있는가: 전공이 학과 지명·계열·가까운 전공이거나, 관심 문장과 겹치거나, 관심 문장 없이 전공 무관
         * (ADR-0026·0028).
         */
        public boolean relevant() {
            return major == MajorTier.DIRECT || major == MajorTier.GROUP || major == MajorTier.NEAR || interestMatch()
                    || (major == MajorTier.OPEN && interestFit == Interest.NOT_GIVEN);
        }

        /** 관심 문장과 겹친다(조금 이상). 지망 점검의 빈 자리 문장도 쓴다. */
        public boolean interestMatch() {
            return interestFit == Interest.SOME || interestFit == Interest.CLOSE;
        }

        public boolean interestClose() {
            return interestFit == Interest.CLOSE;
        }
    }

    /**
     * 지원 불가가 아닌 직무를 HIGH 먼저, 점수 순으로. 관심 유사도(IDF·최댓값)는 지원 불가를 포함한 회차 직무 전부로 잰다 —
     * 판정에 따라 기준이 흔들려 가장 많이 겹친 직무가 지원 불가로 빠졌다고 덜 겹친 직무가 '가장 가깝다'가 되지 않게(ADR-0024).
     *
     * @param departmentId 학생 학과(전공 근거가 학과 지명인지 볼 때)
     */
    public static List<Scored> score(List<Judged> judged, Map<Integer, Features> features, String interestText,
                                     int departmentId) {
        boolean hasInterest = interestText != null && !interestText.isBlank();
        double[] sims = new double[judged.size()];
        double[] raw = new double[judged.size()];
        if (hasInterest) {
            List<String> corpus = new ArrayList<>();
            for (Judged j : judged) {
                corpus.add(features.get(j.requirement().jobId()).text());
            }
            corpus.add(interestText);
            KeywordSimilarity model = new KeywordSimilarity(corpus);
            var query = model.vector(interestText);
            double max = 0;
            for (int i = 0; i < judged.size(); i++) {
                raw[i] = KeywordSimilarity.cosine(query, model.vector(corpus.get(i)));
                sims[i] = raw[i];
                max = Math.max(max, sims[i]);
            }
            for (int i = 0; i < sims.length; i++) {
                sims[i] = max > 0 ? sims[i] / max : 0;
            }
        }
        List<Scored> out = new ArrayList<>();
        for (int i = 0; i < judged.size(); i++) {
            Judged j = judged.get(i);
            if (j.result().verdict() == Verdict.INELIGIBLE) {
                continue;
            }
            Features f = features.get(j.requirement().jobId());
            MajorTier major = tier(j.result().majorMatch(), f.directDepartments(), departmentId);
            Interest interest = !hasInterest ? Interest.NOT_GIVEN
                    : !interestMatch(raw[i], sims[i]) ? Interest.NONE
                    : sims[i] >= HIGH_INTEREST ? Interest.CLOSE : Interest.SOME;
            boolean eligible = j.result().verdict() == Verdict.ELIGIBLE;
            boolean hiring = "HIRING".equals(f.jobType());
            double interestPoints = interest == Interest.SOME || interest == Interest.CLOSE ? W_INTEREST * sims[i] : 0;
            double score = majorPoints(major) + interestPoints + (eligible ? W_ELIGIBLE : 0)
                    + W_STIPEND * stipendScore(f.stipend()) + (hiring ? W_HIRING : 0);
            out.add(new Scored(j, f, score, sims[i], major, interest, grade(major, interest)));
        }
        out.sort(Comparator.comparing((Scored s) -> s.fit() == Fit.HIGH ? 0 : 1)
                .thenComparing(Comparator.comparingDouble(Scored::score).reversed())
                .thenComparingInt(s -> s.judged().requirement().listSeq()));
        return out;
    }

    /** 전공 근거. 선호 전공에 들면(MATCH) 학과를 콕 집은 표기에 들었는지로 학과 지명·계열을 가른다. */
    static MajorTier tier(MajorMatch match, Set<Integer> directDepartments, int departmentId) {
        return switch (match) {
            case OPEN -> MajorTier.OPEN;
            case NEAR -> MajorTier.NEAR;
            case NOT_LISTED -> MajorTier.NONE;
            case MATCH -> directDepartments != null && directDepartments.contains(departmentId)
                    ? MajorTier.DIRECT : MajorTier.GROUP;
        };
    }

    static double majorPoints(MajorTier tier) {
        return switch (tier) {
            case DIRECT -> W_DIRECT;
            case GROUP -> W_GROUP;
            case NEAR -> W_NEAR;
            case OPEN -> W_OPEN;
            case NONE -> 0;
        };
    }

    /**
     * 등급: 맞는 근거(학과 지명·계열, 관심 많이 겹침)가 하나 이상이고 어긋나는 근거(먼 전공, 관심을 적었는데 많이 겹치지
     * 않음)가 없으면 HIGH. 가까운 전공·전공 무관과 관심을 안 적은 것은 맞는 근거도 어긋나는 근거도 아니다.
     */
    static Fit grade(MajorTier major, Interest interest) {
        boolean fits = major == MajorTier.DIRECT || major == MajorTier.GROUP || interest == Interest.CLOSE;
        boolean against = major == MajorTier.NONE || interest == Interest.NONE || interest == Interest.SOME;
        return fits && !against ? Fit.HIGH : Fit.MEDIUM;
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

}
