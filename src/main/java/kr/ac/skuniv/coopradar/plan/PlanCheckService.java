package kr.ac.skuniv.coopradar.plan;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kr.ac.skuniv.coopradar.auth.AuthUser;
import kr.ac.skuniv.coopradar.eligibility.EligibilityDtos.MajorMatch;
import kr.ac.skuniv.coopradar.eligibility.EligibilityDtos.Verdict;
import kr.ac.skuniv.coopradar.eligibility.EligibilityService;
import kr.ac.skuniv.coopradar.eligibility.ProfileInput;
import kr.ac.skuniv.coopradar.plan.PlanDtos.Alternative;
import kr.ac.skuniv.coopradar.plan.PlanDtos.CheckedItem;
import kr.ac.skuniv.coopradar.plan.PlanDtos.PlanCheck;
import kr.ac.skuniv.coopradar.plan.PlanDtos.PlanItem;
import kr.ac.skuniv.coopradar.recommend.FitScorer;
import kr.ac.skuniv.coopradar.recommend.FitScorer.Scored;
import kr.ac.skuniv.coopradar.recommend.RecommendDtos.Fit;
import kr.ac.skuniv.coopradar.recommend.RecommendService;
import kr.ac.skuniv.coopradar.reference.RoundService;
import kr.ac.skuniv.coopradar.signal.Signal;
import kr.ac.skuniv.coopradar.signal.SignalService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 1~3지망 점검(#23). 순위를 정한 지망마다 asOf 기준 모집 신호를 주고, 요건이 맞는 빈 자리를 제안한다.
 * 몰림 경고는 하지 않는다(ADR-0015). 신호 계산은 센터 현황판과 같고, 적합도는 추천(#15)과 같다.
 */
@Service
public class PlanCheckService {

    static final int MAX_ALTERNATIVES = 5;

    private final PlanRepository plans;
    private final EligibilityService eligibility;
    private final RecommendService recommend;
    private final SignalService signals;
    private final RoundService rounds;

    public PlanCheckService(PlanRepository plans, EligibilityService eligibility, RecommendService recommend,
                            SignalService signals, RoundService rounds) {
        this.plans = plans;
        this.eligibility = eligibility;
        this.recommend = recommend;
        this.signals = signals;
        this.rounds = rounds;
    }

    @Transactional(readOnly = true)
    public PlanCheck check(AuthUser user, ProfileInput profile, LocalDate requestedAsOf) {
        var round = rounds.current();
        LocalDate asOf = SignalService.resolveAsOf(round, requestedAsOf);
        Map<Integer, Signal> byJob = signals.byJob(round, asOf);

        List<CheckedItem> items = new ArrayList<>();
        Set<Integer> rankedJobs = new HashSet<>();
        Set<Integer> rankedInstitutions = new HashSet<>();
        for (PlanItem p : plans.items(user.id())) {
            Signal signal = byJob.get(p.jobId());
            if (p.rank() == null || signal == null) {
                continue; // 순위 없는 담은 직무, 지난 회차 직무는 점검하지 않는다
            }
            items.add(new CheckedItem(p.rank(), p.jobId(), p.title(), p.institution(), signal));
            rankedJobs.add(p.jobId());
            rankedInstitutions.add(p.institution().id());
        }

        var judged = eligibility.judgeAll(round.id(), profile);
        List<Scored> scored = recommend.score(round.id(), judged, profile);
        List<Alternative> alternatives = alternatives(scored, byJob, rankedJobs, rankedInstitutions);
        return new PlanCheck(asOf, true, Signal.Source.REPLAY, items, alternatives);
    }

    /** INELIGIBLE(점수 후보에 없음)·CLOSED·남은 자리 0·이미 순위를 정한 직무를 빼고 적합도 → 남은 자리 → 점수 순. */
    static List<Alternative> alternatives(List<Scored> scored, Map<Integer, Signal> byJob, Set<Integer> rankedJobs,
                                          Set<Integer> rankedInstitutions) {
        record Candidate(Scored scored, Signal signal, int remaining) {
        }
        List<Candidate> open = new ArrayList<>();
        for (Scored s : scored) {
            Signal signal = byJob.get(s.jobId());
            if (signal == null || rankedJobs.contains(s.jobId()) || signal.status() == Signal.Status.CLOSED) {
                continue;
            }
            int remaining = signal.headcount() - signal.intent();
            if (remaining > 0) {
                open.add(new Candidate(s, signal, remaining));
            }
        }
        open.sort(Comparator.comparing((Candidate c) -> c.scored().fit() == Fit.HIGH ? 0 : 1)
                .thenComparing(Comparator.comparingInt(Candidate::remaining).reversed())
                .thenComparing(Comparator.comparingDouble((Candidate c) -> c.scored().score()).reversed())
                .thenComparingInt(c -> c.scored().judged().requirement().listSeq()));
        List<Alternative> out = new ArrayList<>();
        for (Candidate c : open.subList(0, Math.min(MAX_ALTERNATIVES, open.size()))) {
            var r = c.scored().judged().result();
            boolean sameInstitution = rankedInstitutions.contains(r.institution().id());
            out.add(new Alternative(r.jobId(), r.title(), r.institution(), r.verdict(), c.scored().fit(), c.remaining(),
                    c.signal(), why(sameInstitution, r.majorMatch(), c.scored().interest() >= FitScorer.HIGH_INTEREST,
                    r.verdict(), c.signal().intent(), c.remaining())));
        }
        return out;
    }

    /** 왜 이 자리를 제안하는지(규칙 문장). 앞: 나와의 관계, 뒤: 지금 신호. */
    static String why(boolean sameInstitution, MajorMatch major, boolean interestClose, Verdict verdict, int intent,
                      int remaining) {
        String first;
        if (sameInstitution) {
            first = "지망한 직무와 같은 기관이고";
        } else if (major == MajorMatch.MATCH) {
            first = "선호 전공에 소속 학과가 들어 있고";
        } else if (major == MajorMatch.OPEN) {
            first = "전공 무관 자리이고";
        } else if (interestClose) {
            first = "관심 분야와 직무 내용이 가깝고";
        } else if (verdict == Verdict.ELIGIBLE) {
            first = "지원 조건을 모두 통과했고";
        } else {
            first = "확인할 조건만 챙기면 되고";
        }
        String second = intent == 0 ? "지금 지원 의사가 0명이에요." : "남은 자리가 " + remaining + "석이에요.";
        return first + ", " + second;
    }
}
