package kr.ac.skuniv.coopradar.plan;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kr.ac.skuniv.coopradar.auth.AuthUser;
import kr.ac.skuniv.coopradar.eligibility.EligibilityDtos.Verdict;
import kr.ac.skuniv.coopradar.eligibility.EligibilityService;
import kr.ac.skuniv.coopradar.eligibility.ProfileInput;
import kr.ac.skuniv.coopradar.plan.PlanDtos.Alternative;
import kr.ac.skuniv.coopradar.plan.PlanDtos.CheckedItem;
import kr.ac.skuniv.coopradar.plan.PlanDtos.PlanCheck;
import kr.ac.skuniv.coopradar.plan.PlanDtos.PlanItem;
import kr.ac.skuniv.coopradar.recommend.FitScorer.Scored;
import kr.ac.skuniv.coopradar.recommend.RecommendService;
import kr.ac.skuniv.coopradar.reference.RoundService;
import kr.ac.skuniv.coopradar.signal.Signal;
import kr.ac.skuniv.coopradar.signal.SignalService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 1~3지망 점검(#23). 순위를 정한 지망마다 asOf 기준 관심 신호를 주고, 요건이 맞는 빈 자리를 제안한다(ADR-0016).
 * 몰림 경고는 하지 않는다(ADR-0015). 신호 계산은 센터 현황판과 같지만, 실제 담은 수에서 본인은 뺀다 — 다른 사람이
 * 몇 명 담았는지를 보여 준다(ADR-0019). 적합도는 추천(#15)과 같다.
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
        Map<Integer, Signal> byJob = signals.byJob(round, asOf, user.id());

        List<CheckedItem> items = new ArrayList<>();
        Set<Integer> plannedJobs = new HashSet<>();
        Integer firstChoiceInstitution = null;
        for (PlanItem p : plans.items(user.id())) {
            plannedJobs.add(p.jobId());
            Signal signal = byJob.get(p.jobId());
            if (p.rank() == null || signal == null) {
                continue; // 순위 없는 담은 직무, 지난 회차 직무는 점검하지 않는다
            }
            items.add(new CheckedItem(p.rank(), p.jobId(), p.title(), p.institution(), signal));
            if (p.rank() == 1) {
                firstChoiceInstitution = p.institution().id();
            }
        }

        var judged = eligibility.judgeAll(round.id(), profile);
        List<Scored> scored = recommend.score(round.id(), judged, profile);
        List<Alternative> alternatives = alternatives(scored, byJob, plannedJobs, firstChoiceInstitution);
        return new PlanCheck(asOf, true, Signal.Source.REPLAY, items, alternatives);
    }

    /**
     * 대안(ADR-0016): ELIGIBLE · CLOSED 아님 · 남은 자리 > 0 · 이미 담은 직무 아님.
     * 적합도 점수(추천과 같은 점수, ADR-0018) → 남은 자리 → 리스트 순번 순으로 최대 5개.
     */
    static List<Alternative> alternatives(List<Scored> scored, Map<Integer, Signal> byJob, Set<Integer> plannedJobs,
                                          Integer firstChoiceInstitution) {
        record Candidate(Scored scored, Signal signal, int remaining) {
        }
        List<Candidate> open = new ArrayList<>();
        for (Scored s : scored) {
            Signal signal = byJob.get(s.jobId());
            if (signal == null || s.judged().result().verdict() != Verdict.ELIGIBLE || plannedJobs.contains(s.jobId())
                    || signal.status() == Signal.Status.CLOSED) {
                continue;
            }
            int remaining = signal.headcount() - signal.interest();
            if (remaining > 0) {
                open.add(new Candidate(s, signal, remaining));
            }
        }
        open.sort(Comparator.comparingDouble((Candidate c) -> c.scored().score()).reversed()
                .thenComparing(Comparator.comparingInt(Candidate::remaining).reversed())
                .thenComparingInt(c -> c.scored().judged().requirement().listSeq()));
        List<Alternative> out = new ArrayList<>();
        for (Candidate c : open.subList(0, Math.min(MAX_ALTERNATIVES, open.size()))) {
            var r = c.scored().judged().result();
            boolean sameAsFirst = firstChoiceInstitution != null && firstChoiceInstitution == r.institution().id();
            out.add(new Alternative(r.jobId(), r.title(), r.institution(), r.verdict(), c.scored().fit(), c.remaining(),
                    c.signal(), why(sameAsFirst, c.signal().interest(), c.remaining())));
        }
        return out;
    }

    /**
     * 규칙 문장(ADR-0016). 앞: 1지망과 같은 기관이면 '1지망과 같은 기관의 직무이고', 아니면 '관심 분야와 가깝고'.
     * 뒤: 관심(담은 사람)이 0이면 '지금 담은 사람이 0명이에요.', 아니면 '남은 자리가 N개예요.'
     */
    static String why(boolean sameAsFirstChoice, int interest, int remaining) {
        String first = sameAsFirstChoice ? "1지망과 같은 기관의 직무이고" : "관심 분야와 가깝고";
        String second = interest == 0 ? "지금 담은 사람이 0명이에요." : "남은 자리가 " + remaining + "개예요.";
        return first + ", " + second;
    }
}
