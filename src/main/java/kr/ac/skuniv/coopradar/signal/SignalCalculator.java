package kr.ac.skuniv.coopradar.signal;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import kr.ac.skuniv.coopradar.signal.Signal.Status;

/**
 * 리플레이 일별 신호 → asOf 기준 모집 신호(docs/api/README.md 'Signal'). DB를 모르는 순수 계산이다.
 * <ul>
 *   <li>intent·interest: asOf 당일까지 일별 수의 합</li>
 *   <li>status: asOf가 회차 종료일보다 뒤이거나 closesOn ≤ asOf면 CLOSED, 아니면 OPEN</li>
 *   <li>expectedFullOn: 최근 3일(모집 시작 전 날은 빼고) 지원 의사 하루 평균으로 남은 자리를 외삽한 날.
 *       이미 정원에 닿았거나, 마감됐거나, 최근 증가가 없거나, 지원할 수 있는 마지막 날(회차 종료일, 또는 closesOn 전날)을
 *       넘기면 null</li>
 * </ul>
 */
public final class SignalCalculator {

    static final int TREND_DAYS = 3;

    private SignalCalculator() {
    }

    /**
     * @param daily 직무 하나의 일별 신호(그날 새로 생긴 수). 없는 날은 0이다
     */
    public static Signal compute(Map<LocalDate, Daily> daily, int headcount, LocalDate closesOn, String closeReason,
                                 boolean closesOnIsVirtual, LocalDate recruitStart, LocalDate recruitEnd,
                                 LocalDate asOf) {
        int intent = 0;
        int interest = 0;
        for (var e : daily.entrySet()) {
            if (!e.getKey().isAfter(asOf)) {
                intent += e.getValue().intent();
                interest += e.getValue().interest();
            }
        }
        boolean closed = asOf.isAfter(recruitEnd) || (closesOn != null && !closesOn.isAfter(asOf));
        Status status = closed ? Status.CLOSED : Status.OPEN;
        LocalDate expected = closed ? null
                : expectedFullOn(daily, intent, headcount, closesOn, recruitStart, recruitEnd, asOf);
        return new Signal(intent, interest, headcount, round2((double) intent / headcount), status, closesOn,
                closeReason, closesOnIsVirtual, expected);
    }

    static LocalDate expectedFullOn(Map<LocalDate, Daily> daily, int intent, int headcount, LocalDate closesOn,
                                    LocalDate recruitStart, LocalDate recruitEnd, LocalDate asOf) {
        if (intent >= headcount) {
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
            Daily row = daily.get(d);
            recent += row == null ? 0 : row.intent();
        }
        if (recent == 0) {
            return null;
        }
        double perDay = (double) recent / days;
        LocalDate reach = asOf.plusDays((long) Math.ceil((headcount - intent) / perDay));
        LocalDate lastOpenDay = closesOn != null && closesOn.minusDays(1).isBefore(recruitEnd)
                ? closesOn.minusDays(1) : recruitEnd;
        return reach.isAfter(lastOpenDay) ? null : reach;
    }

    static double round2(double value) {
        return Math.round(value * 100) / 100.0;
    }

    /** 하루에 새로 생긴 관심·지원 의사 수. */
    public record Daily(int interest, int intent) {
    }
}
