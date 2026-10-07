package kr.ac.skuniv.coopradar.eligibility;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import kr.ac.skuniv.coopradar.common.ApiException;
import kr.ac.skuniv.coopradar.eligibility.EligibilityDtos.Eligibility;
import kr.ac.skuniv.coopradar.eligibility.EligibilityDtos.EligibilityJob;
import kr.ac.skuniv.coopradar.eligibility.EligibilityDtos.Summary;
import kr.ac.skuniv.coopradar.eligibility.EligibilityDtos.Verdict;
import kr.ac.skuniv.coopradar.job.RoundRef;
import kr.ac.skuniv.coopradar.reference.RoundService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회차 직무 전부의 3층 판정(#14). 프로필은 요청 본문으로만 받고 저장·로그하지 않는다(ADR-0008).
 * 추천(#15)·지망 점검(#23)도 이 판정을 다시 쓴다.
 */
@Service
public class EligibilityService {

    private final EligibilityRepository repository;
    private final RoundService rounds;
    private final FitOrder fitOrder;

    public EligibilityService(EligibilityRepository repository, RoundService rounds, FitOrder fitOrder) {
        this.repository = repository;
        this.rounds = rounds;
        this.fitOrder = fitOrder;
    }

    /** 판정 결과와 그 판정에 쓴 요건. 추천·지망 점검이 요건(전공 매핑 등)을 다시 쓴다. */
    public record Judged(JobRequirement requirement, EligibilityJob result) {
    }

    @Transactional(readOnly = true)
    public Eligibility check(ProfileInput profile) {
        var round = rounds.current();
        List<Judged> judged = judgeAll(round.id(), profile);
        LocalDate asOf = round.replay().defaultAsOf(); // 추천과 같은 기준일(ADR-0016)
        List<EligibilityJob> jobs = order(judged, fitOrder.rank(round.id(), judged, profile), asOf).stream()
                .map(Judged::result).toList();
        return new Eligibility(new RoundRef(round.id(), round.termCode()), summary(jobs), jobs);
    }

    /**
     * 목록 순서(ADR-0027): 판정(지원 가능 → 확인 필요 → 지원 불가) → 같은 판정 안에서 모집 중 먼저, 기준일에 마감된 직무는
     * 아래 → 모집 중인 지원 가능·확인 필요는 적합도 순(추천과 같음) → 나머지(지원 불가, 마감)는 센터 리스트 순번.
     *
     * @param fitRank 지원 불가가 아닌 직무 id의 적합도 순위({@link FitOrder})
     */
    static List<Judged> order(List<Judged> judged, List<Integer> fitRank, LocalDate asOf) {
        Map<Integer, Integer> rank = new HashMap<>();
        for (int i = 0; i < fitRank.size(); i++) {
            rank.putIfAbsent(fitRank.get(i), i);
        }
        Comparator<Judged> order = Comparator
                .comparing((Judged j) -> j.result().verdict())
                .thenComparing(j -> closedOn(j, asOf))
                .thenComparingInt(j -> j.result().verdict() == Verdict.INELIGIBLE || closedOn(j, asOf)
                        ? Integer.MAX_VALUE : rank.getOrDefault(j.requirement().jobId(), Integer.MAX_VALUE))
                .thenComparingInt(j -> j.requirement().listSeq());
        return judged.stream().sorted(order).toList();
    }

    /** 기준일에 마감됐는지(closesOn ≤ 기준일 — 이 날부터 지원 불가). 추천과 같은 기준이다. */
    public static boolean closedOn(Judged judged, LocalDate asOf) {
        LocalDate closesOn = judged.requirement().closing().closesOn();
        return closesOn != null && asOf != null && !closesOn.isAfter(asOf);
    }

    /**
     * 회차 직무 전부를 리스트 순번대로 판정한다. 학과가 시드에 없으면 400 INVALID_INPUT(profile.departmentId),
     * 자격증 코드가 코드표에 없으면 400 INVALID_INPUT(profile.certificates).
     */
    @Transactional(readOnly = true)
    public List<Judged> judgeAll(int roundId, ProfileInput profile) {
        String department = repository.departmentName(profile.departmentId())
                .orElseThrow(() -> ApiException.invalid("profile.departmentId", "GET /api/departments의 id"));
        List<String> certificates = profile.certificates();
        if (certificates != null && (certificates.stream().anyMatch(Objects::isNull)
                || !repository.unknownCertificates(certificates).isEmpty())) {
            throw ApiException.invalid("profile.certificates", "GET /api/certificates의 code");
        }
        return repository.requirements(roundId).stream()
                .map(r -> new Judged(r, EligibilityRules.judge(r, profile, department)))
                .toList();
    }

    static Summary summary(List<EligibilityJob> jobs) {
        int eligible = 0;
        int needsCheck = 0;
        int ineligible = 0;
        for (EligibilityJob j : jobs) {
            switch (j.verdict()) {
                case ELIGIBLE -> eligible++;
                case NEEDS_CHECK -> needsCheck++;
                case INELIGIBLE -> ineligible++;
            }
        }
        return new Summary(jobs.size(), eligible, needsCheck, ineligible);
    }
}
