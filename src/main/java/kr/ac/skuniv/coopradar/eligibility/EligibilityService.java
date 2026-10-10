package kr.ac.skuniv.coopradar.eligibility;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import kr.ac.skuniv.coopradar.common.ApiException;
import kr.ac.skuniv.coopradar.eligibility.EligibilityDtos.Eligibility;
import kr.ac.skuniv.coopradar.eligibility.EligibilityDtos.EligibilityJob;
import kr.ac.skuniv.coopradar.eligibility.EligibilityDtos.EligibilityRow;
import kr.ac.skuniv.coopradar.eligibility.EligibilityDtos.NcsRef;
import kr.ac.skuniv.coopradar.eligibility.EligibilityDtos.Summary;
import kr.ac.skuniv.coopradar.eligibility.EligibilityDtos.Verdict;
import kr.ac.skuniv.coopradar.job.RoundRef;
import kr.ac.skuniv.coopradar.reference.ReferenceDates;
import kr.ac.skuniv.coopradar.reference.ReferenceDates.AsOf;
import kr.ac.skuniv.coopradar.reference.RoundService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회차 직무 전부의 3층 판정(#14). 프로필은 요청 본문(없으면 저장한 프로필)으로 받고 저장·로그하지 않는다(ADR-0008).
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

    /**
     * #14. 행마다 NCS 세분류·조회 수·내 담기·지망 순위를 붙인다(목록이 직무마다 다른 API를 부르지 않게, ADR-0035).
     *
     * @param userId 담기·순위를 볼 계정
     */
    @Transactional(readOnly = true)
    public Eligibility check(long userId, ProfileInput profile) {
        var round = rounds.current();
        List<Judged> judged = judgeAll(round.id(), profile);
        AsOf asOf = ReferenceDates.recruit(round); // 추천·탐색과 같은 모집 판정 기준일(ADR-0016·0035)
        List<EligibilityJob> jobs = order(judged, fitOrder.rank(round.id(), judged, profile), asOf).stream()
                .map(Judged::result).toList();
        Map<Integer, NcsRef> ncs = repository.ncsByJob(round.id());
        Map<Integer, Integer> views = repository.viewsByJob(round.id());
        Map<Integer, Integer> ranks = new HashMap<>();
        Set<Integer> planned = new HashSet<>();
        repository.plan(userId).forEach(p -> {
            planned.add(p.jobId());
            if (p.rank() != null) {
                ranks.put(p.jobId(), p.rank());
            }
        });
        List<EligibilityRow> rows = jobs.stream().map(j -> EligibilityRow.of(j, ncs.get(j.jobId()),
                views.getOrDefault(j.jobId(), 0), planned.contains(j.jobId()), ranks.get(j.jobId()))).toList();
        return new Eligibility(new RoundRef(round.id(), round.termCode()), summary(jobs), rows);
    }

    /** 저장한 프로필로 본 직무 하나의 판정(#17 myEligibility). 그 직무 회차의 직무 전부를 판정한 뒤 고른다. */
    @Transactional(readOnly = true)
    public java.util.Optional<EligibilityJob> judgeOne(int roundId, ProfileInput profile, int jobId) {
        return judgeAll(roundId, profile).stream().map(Judged::result).filter(j -> j.jobId() == jobId).findFirst();
    }

    /**
     * 목록 순서(ADR-0027): 판정(지원 가능 → 확인 필요 → 지원 불가) → 같은 판정 안에서 모집 중 먼저, 기준일에 마감된 직무는
     * 아래 → 모집 중인 지원 가능·확인 필요는 적합도 순(추천과 같음) → 나머지(지원 불가, 마감)는 센터 리스트 순번.
     *
     * @param fitRank 지원 불가가 아닌 직무 id의 적합도 순위({@link FitOrder})
     */
    static List<Judged> order(List<Judged> judged, List<Integer> fitRank, AsOf asOf) {
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

    /** 기준일에 마감됐는지({@link AsOf#closed} — 마감 규칙은 하나다). */
    public static boolean closedOn(Judged judged, AsOf asOf) {
        return asOf.closed(judged.requirement().closing().closesOn());
    }

    /** 모집 종료일을 모를 때(추천 — 기준일이 늘 모집기간 안이라 closesOn만 본다). */
    public static boolean closedOn(Judged judged, LocalDate asOf) {
        return closedOn(judged, new AsOf(asOf, null));
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
        // 가까운 전공(ADR-0028): 내 학과와 같은 묶음의 학과 중, 직무가 학과를 콕 집어 적은 학과만
        Set<Integer> near = repository.nearDepartments(profile.departmentId());
        Map<Integer, Set<Integer>> named = near.isEmpty() ? Map.of() : repository.namedDepartments(roundId);
        return repository.requirements(roundId).stream()
                .map(r -> new Judged(r, EligibilityRules.judge(r, profile, department,
                        intersect(near, named.getOrDefault(r.jobId(), Set.of())))))
                .toList();
    }

    private static Set<Integer> intersect(Set<Integer> a, Set<Integer> b) {
        Set<Integer> out = new HashSet<>(a);
        out.retainAll(b);
        return out;
    }

    /** 내 학과와 같은 묶음의 가까운 학과(ADR-0028). 이유 문장이 가까운 학과가 든 선호 전공 표기를 고를 때 쓴다. */
    @Transactional(readOnly = true)
    public Set<Integer> nearDepartments(int departmentId) {
        return repository.nearDepartments(departmentId);
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
