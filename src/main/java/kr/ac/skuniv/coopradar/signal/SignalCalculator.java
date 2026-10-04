package kr.ac.skuniv.coopradar.signal;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import kr.ac.skuniv.coopradar.signal.Signal.Status;

/**
 * 일별 관심(리플레이 가상 값) + 실제 담은 수 → asOf 기준 모집 신호(docs/api/README.md 'Signal'). DB를 모르는 순수 계산이다.
 * <ul>
 *   <li>실제 담은 수(live)는 기준일(liveOn = replay.defaultAsOf, 시연 속 '오늘')에 새로 생긴 관심으로 더한다.
 *       기준일에 이미 마감(closesOn ≤ liveOn)인 직무에는 더하지 않는다(ADR-0019)</li>
 *   <li>interest: asOf 당일까지 일별 수의 합. liveInterest: 그중 실제 값(asOf ≥ liveOn일 때만)</li>
 *   <li>status: asOf가 회차 종료일보다 뒤이거나 closesOn ≤ asOf면 CLOSED, 아니면 OPEN</li>
 *   <li>expectedFullOn: 최근 3일(모집 시작 전 날은 빼고) 관심 하루 평균으로 남은 자리를 외삽한 날.
 *       이미 정원에 닿았거나, 마감됐거나, 최근 증가가 없거나, 지원할 수 있는 마지막 날(회차 종료일, 또는 closesOn 전날)을
 *       넘기면 null</li>
 * </ul>
 */
public final class SignalCalculator {

    static final int TREND_DAYS = 3;

    private SignalCalculator() {
    }

    /** 실제 담은 수 없이(가상 값만) 계산한다. */
    public static Signal compute(Map<LocalDate, Integer> daily, int headcount, LocalDate closesOn, String closeReason,
                                 boolean closesOnIsVirtual, LocalDate recruitStart, LocalDate recruitEnd,
                                 LocalDate asOf) {
        return compute(daily, 0, asOf, headcount, closesOn, closeReason, closesOnIsVirtual, recruitStart, recruitEnd,
                asOf);
    }

    /**
     * @param daily  직무 하나의 일별 관심(그날 새로 생긴 가상 수). 없는 날은 0이다
     * @param live   실제 사용자가 담은 수(지금 기준)
     * @param liveOn live를 더할 날(replay.defaultAsOf)
     */
    public static Signal compute(Map<LocalDate, Integer> daily, int live, LocalDate liveOn, int headcount,
                                 LocalDate closesOn, String closeReason, boolean closesOnIsVirtual,
                                 LocalDate recruitStart, LocalDate recruitEnd, LocalDate asOf) {
        Map<LocalDate, Integer> all = daily;
        boolean liveCounts = live > 0 && (closesOn == null || closesOn.isAfter(liveOn));
        if (liveCounts) {
            all = new HashMap<>(daily);
            all.merge(liveOn, live, Integer::sum);
        }
        int interest = 0;
        for (var e : all.entrySet()) {
            if (!e.getKey().isAfter(asOf)) {
                interest += e.getValue();
            }
        }
        int liveInterest = liveCounts && !liveOn.isAfter(asOf) ? live : 0;
        boolean closed = asOf.isAfter(recruitEnd) || (closesOn != null && !closesOn.isAfter(asOf));
        Status status = closed ? Status.CLOSED : Status.OPEN;
        LocalDate expected = closed ? null
                : expectedFullOn(all, interest, headcount, closesOn, recruitStart, recruitEnd, asOf);
        return new Signal(interest, liveInterest, headcount, round2((double) interest / headcount), status, closesOn,
                closeReason, closesOnIsVirtual, expected);
    }

    static LocalDate expectedFullOn(Map<LocalDate, Integer> daily, int interest, int headcount, LocalDate closesOn,
                                    LocalDate recruitStart, LocalDate recruitEnd, LocalDate asOf) {
        if (interest >= headcount) {
            return null;
        }
        LocalDate from = asOf.minusDays(TREND_DAYS - 1L);
        if (from.isBefore(recruitStart)) {
            from = recruitStart;
        }
        long days = ChronoUnit.DAYS.between(from, asOf) + 1;
        if (days <= 0) {
            return null;
        }
        int recent = 0;
        for (LocalDate d = from; !d.isAfter(asOf); d = d.plusDays(1)) {
            recent += daily.getOrDefault(d, 0);
        }
        if (recent == 0) {
            return null;
        }
        double perDay = (double) recent / days;
        LocalDate reach = asOf.plusDays((long) Math.ceil((headcount - interest) / perDay));
        LocalDate lastOpenDay = closesOn != null && closesOn.minusDays(1).isBefore(recruitEnd)
                ? closesOn.minusDays(1) : recruitEnd;
        return reach.isAfter(lastOpenDay) ? null : reach;
    }

    static double round2(double value) {
        return Math.round(value * 100) / 100.0;
    }
}
