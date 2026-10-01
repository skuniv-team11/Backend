package kr.ac.skuniv.coopradar.plan;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import kr.ac.skuniv.coopradar.eligibility.EligibilityDtos.Verdict;
import kr.ac.skuniv.coopradar.eligibility.ProfileInput;
import kr.ac.skuniv.coopradar.job.InstitutionRef;
import kr.ac.skuniv.coopradar.recommend.RecommendDtos.Fit;
import kr.ac.skuniv.coopradar.signal.Signal;

/** 지망 API의 요청·응답 모양(docs/api #19~#22: me-plan.json, me-plan-items.request.json, me-plan-ranks.request.json). */
public final class PlanDtos {

    private PlanDtos() {
    }

    /** 담은 직무 1개. rank가 null이면 순위를 안 정한 것이다. */
    public record PlanItem(int jobId, String title, InstitutionRef institution, Integer rank, OffsetDateTime addedAt) {
    }

    /** 순위가 있는 것 먼저(1 → 3), 그다음 담은 순. */
    public record Plan(List<PlanItem> items) {
    }

    public record PlanAddRequest(@NotNull(message = "필수예요") Long jobId) {
    }

    /**
     * 순위 전체. 범위(1~3)·중복·담지 않은 직무·4개 이상은 서비스가 400 RANK_INVALID로 막는다(README '지망').
     * 형식(빠진 값·타입)만 여기서 INVALID_INPUT으로 막는다.
     */
    public record PlanRanksRequest(@NotNull(message = "필수예요") List<@NotNull(message = "필수예요") @Valid RankEntry> ranks) {
    }

    public record RankEntry(@NotNull(message = "필수예요") Long jobId, @NotNull(message = "필수예요") Integer rank) {
    }

    /** 지망 점검 요청(#23). asOf를 생략하면 rounds/current의 replay.defaultAsOf. */
    public record PlanCheckRequest(@NotNull(message = "필수예요") @Valid ProfileInput profile, LocalDate asOf) {
    }

    public record CheckedItem(int rank, int jobId, String title, InstitutionRef institution, Signal signal) {
    }

    /** 요건이 맞는 빈 자리. remaining = 정원 − asOf까지 지원 의사(1 이상). why는 규칙 문장이다. */
    public record Alternative(int jobId, String title, InstitutionRef institution, Verdict verdict, Fit fit,
                              int remaining, Signal signal, String why) {
    }

    /** 모집 신호는 리플레이 가상 데이터라 isVirtual·signalSource를 함께 준다. 경고 문장은 없다(ADR-0015). */
    public record PlanCheck(LocalDate asOf, boolean isVirtual, Signal.Source signalSource, List<CheckedItem> items,
                            List<Alternative> alternatives) {
    }
}
