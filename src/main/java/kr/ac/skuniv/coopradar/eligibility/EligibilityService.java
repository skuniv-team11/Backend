package kr.ac.skuniv.coopradar.eligibility;

import java.util.Comparator;
import java.util.List;
import kr.ac.skuniv.coopradar.common.ApiException;
import kr.ac.skuniv.coopradar.eligibility.EligibilityDtos.Eligibility;
import kr.ac.skuniv.coopradar.eligibility.EligibilityDtos.EligibilityJob;
import kr.ac.skuniv.coopradar.eligibility.EligibilityDtos.Summary;
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

    /** 목록 순서: 지원 가능 → 확인 필요 → 지원 불가, 같은 판정 안에서는 센터 리스트 순번. */
    private static final Comparator<Judged> ORDER = Comparator
            .comparing((Judged j) -> j.result().verdict())
            .thenComparingInt(j -> j.requirement().listSeq());

    private final EligibilityRepository repository;
    private final RoundService rounds;

    public EligibilityService(EligibilityRepository repository, RoundService rounds) {
        this.repository = repository;
        this.rounds = rounds;
    }

    /** 판정 결과와 그 판정에 쓴 요건. 추천·지망 점검이 요건(전공 매핑 등)을 다시 쓴다. */
    public record Judged(JobRequirement requirement, EligibilityJob result) {
    }

    @Transactional(readOnly = true)
    public Eligibility check(ProfileInput profile) {
        var round = rounds.current();
        List<Judged> judged = judgeAll(round.id(), profile);
        List<EligibilityJob> jobs = judged.stream().sorted(ORDER).map(Judged::result).toList();
        return new Eligibility(new RoundRef(round.id(), round.termCode()), summary(jobs), jobs);
    }

    /** 회차 직무 전부를 리스트 순번대로 판정한다. 학과가 시드에 없으면 400 INVALID_INPUT(profile.departmentId). */
    @Transactional(readOnly = true)
    public List<Judged> judgeAll(int roundId, ProfileInput profile) {
        String department = repository.departmentName(profile.departmentId())
                .orElseThrow(() -> ApiException.invalid("profile.departmentId", "GET /api/departments의 id"));
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
