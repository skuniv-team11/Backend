package kr.ac.skuniv.coopradar.recommend;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.ac.skuniv.coopradar.eligibility.EligibilityDtos.Layer;
import kr.ac.skuniv.coopradar.eligibility.EligibilityDtos.Result;
import kr.ac.skuniv.coopradar.eligibility.EligibilityDtos.Verdict;
import kr.ac.skuniv.coopradar.eligibility.EligibilityService;
import kr.ac.skuniv.coopradar.eligibility.EligibilityService.Judged;
import kr.ac.skuniv.coopradar.eligibility.ProfileInput;
import kr.ac.skuniv.coopradar.job.RoundRef;
import kr.ac.skuniv.coopradar.recommend.FitScorer.Scored;
import kr.ac.skuniv.coopradar.recommend.RecommendDtos.Blocked;
import kr.ac.skuniv.coopradar.recommend.RecommendDtos.Citation;
import kr.ac.skuniv.coopradar.recommend.RecommendDtos.Recommendation;
import kr.ac.skuniv.coopradar.recommend.RecommendDtos.Recommendations;
import kr.ac.skuniv.coopradar.reference.RoundService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 적합도 추천(#15, ADR-0017). 판정에서 지원 불가가 아닌 직무를 규칙+키워드 점수로 줄 세워 상위 5개를 준다.
 * 실행 중 외부 호출이 없다. 이유 문장은 규칙 템플릿을 바로 주고, LLM 문장은 #16이 따로 만든다.
 * 지망 점검(#23)도 같은 점수로 빈 자리의 적합도를 정한다.
 */
@Service
public class RecommendService {

    static final int LIMIT = 5;
    static final String REASON_PENDING = "PENDING";

    private final EligibilityService eligibility;
    private final RecommendRepository repository;
    private final RoundService rounds;

    public RecommendService(EligibilityService eligibility, RecommendRepository repository, RoundService rounds) {
        this.eligibility = eligibility;
        this.repository = repository;
        this.rounds = rounds;
    }

    @Transactional(readOnly = true)
    public Recommendations recommend(ProfileInput profile) {
        var round = rounds.current();
        List<Judged> judged = eligibility.judgeAll(round.id(), profile);
        List<Scored> scored = score(round.id(), judged, profile);
        List<Recommendation> items = new ArrayList<>();
        for (Scored s : scored.subList(0, Math.min(LIMIT, scored.size()))) {
            var r = s.judged().result();
            items.add(new Recommendation(items.size() + 1, r.jobId(), r.title(), r.institution(), r.verdict(), s.fit(),
                    s.features().jobType(), s.features().stipend(), s.reasonTemplate(), REASON_PENDING,
                    citations(r.jobId(), r.institution().id())));
        }
        return new Recommendations(new RoundRef(round.id(), round.termCode()), items,
                items.isEmpty() ? blockedBy(judged) : List.of());
    }

    /** 지원 불가가 아닌 직무 전부의 점수(높은 순). 지망 점검이 빈 자리 적합도에 쓴다. */
    @Transactional(readOnly = true)
    public List<Scored> score(int roundId, List<Judged> judged, ProfileInput profile) {
        List<Judged> candidates = judged.stream().filter(j -> j.result().verdict() != Verdict.INELIGIBLE).toList();
        return FitScorer.score(candidates, repository.features(roundId), profile.interestText());
    }

    /** 근거 인용 최대 2개: 같은 기관 선배 수기 → 운영계획서 직무 개요. */
    @Transactional(readOnly = true)
    public List<Citation> citations(int jobId, int institutionId) {
        List<Citation> out = new ArrayList<>();
        repository.testimonialCitation(institutionId).ifPresent(out::add);
        repository.planCitation(jobId).ifPresent(out::add);
        return out;
    }

    /** 학교 규정에서 막힌 항목별 직무 수(추천이 0개일 때만). */
    static List<Blocked> blockedBy(List<Judged> judged) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Judged j : judged) {
            j.result().reasons().stream()
                    .filter(r -> r.layer() == Layer.SCHOOL_RULE && r.result() == Result.NOT_MET)
                    .forEach(r -> counts.merge(r.item(), 1, Integer::sum));
        }
        return counts.entrySet().stream().map(e -> new Blocked(e.getKey(), e.getValue())).toList();
    }
}
