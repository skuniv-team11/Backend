package kr.ac.skuniv.coopradar.plan;

import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import kr.ac.skuniv.coopradar.auth.AuthUser;
import kr.ac.skuniv.coopradar.common.ApiException;
import kr.ac.skuniv.coopradar.common.ErrorCode;
import kr.ac.skuniv.coopradar.plan.PlanDtos.Plan;
import kr.ac.skuniv.coopradar.plan.PlanDtos.RankEntry;
import kr.ac.skuniv.coopradar.reference.RoundService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 담은 직무와 1~3지망 순위(#19~#22). 담기는 현재 회차 직무만 된다. */
@Service
public class PlanService {

    static final int MAX_RANK = 3;

    private final PlanRepository plans;
    private final RoundService rounds;
    private final Clock clock;

    public PlanService(PlanRepository plans, RoundService rounds, Clock clock) {
        this.plans = plans;
        this.rounds = rounds;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Plan get(AuthUser user) {
        return new Plan(plans.items(user.id()));
    }

    /** 새로 담았으면 true(201), 이미 담겨 있으면 false(200). 현재 회차에 없는 직무는 400 INVALID_INPUT. */
    @Transactional
    public boolean add(AuthUser user, long jobId) {
        if (!plans.jobInRound(jobId, rounds.current().id())) {
            throw ApiException.invalid("jobId", "현재 회차의 직무 id");
        }
        return plans.add(user.id(), jobId, clock.instant());
    }

    @Transactional
    public void remove(AuthUser user, long jobId) {
        if (!plans.remove(user.id(), jobId)) {
            throw new ApiException(ErrorCode.PLAN_ITEM_NOT_FOUND, "담지 않은 직무예요");
        }
    }

    /** 순위 전체를 바꾼다. 여기 없는 담은 직무는 순위가 지워진다. */
    @Transactional
    public Plan setRanks(AuthUser user, List<RankEntry> ranks) {
        validate(ranks, new HashSet<>(plans.jobIds(user.id())));
        plans.replaceRanks(user.id(), ranks);
        return new Plan(plans.items(user.id()));
    }

    static void validate(List<RankEntry> ranks, Set<Integer> planned) {
        if (ranks.size() > MAX_RANK) {
            throw rankInvalid("순위는 3개까지 정할 수 있어요");
        }
        Set<Integer> seenRanks = new HashSet<>();
        Set<Long> seenJobs = new HashSet<>();
        for (RankEntry r : ranks) {
            if (r.rank() < 1 || r.rank() > MAX_RANK) {
                throw rankInvalid("순위는 1~3이에요");
            }
            if (!seenRanks.add(r.rank())) {
                throw rankInvalid("같은 순위를 두 번 쓸 수 없어요");
            }
            if (!seenJobs.add(r.jobId())) {
                throw rankInvalid("한 직무에 순위를 두 번 줄 수 없어요");
            }
            if (r.jobId() > Integer.MAX_VALUE || !planned.contains(r.jobId().intValue())) {
                throw rankInvalid("담은 직무에만 순위를 정할 수 있어요");
            }
        }
    }

    private static ApiException rankInvalid(String message) {
        return new ApiException(ErrorCode.RANK_INVALID, message);
    }
}
