package kr.ac.skuniv.coopradar.signal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Map;
import kr.ac.skuniv.coopradar.signal.Signal.Status;
import kr.ac.skuniv.coopradar.signal.SignalCalculator.Daily;
import org.junit.jupiter.api.Test;

/** 모집 신호 계산(docs/api/README.md 'Signal'). DB 없이 본다. 회차는 7/13~7/24. */
class SignalCalculatorTest {

    private static final LocalDate START = LocalDate.of(2026, 7, 13);
    private static final LocalDate END = LocalDate.of(2026, 7, 24);

    @Test
    void asOf_당일까지만_더하고_비율은_소수_둘째_자리() {
        var daily = Map.of(day(13), new Daily(2, 1), day(15), new Daily(1, 1), day(19), new Daily(3, 1));
        Signal s = compute(daily, 3, null, day(18));
        assertThat(s.intent()).isEqualTo(2);
        assertThat(s.interest()).isEqualTo(3);
        assertThat(s.ratio()).isEqualTo(0.67);
        assertThat(s.status()).isEqualTo(Status.OPEN);
        assertThat(compute(daily, 3, null, day(15)).ratio()).isEqualTo(0.67);
        assertThat(compute(daily, 3, null, day(14)).ratio()).isEqualTo(0.33);
        assertThat(compute(Map.of(), 2, null, day(18)).ratio()).isEqualTo(0.0);
    }

    @Test
    void 마감일_당일부터_CLOSED이고_정원을_넘어도_OPEN() {
        assertThat(compute(Map.of(), 1, day(18), day(17)).status()).isEqualTo(Status.OPEN);
        assertThat(compute(Map.of(), 1, day(18), day(18)).status()).isEqualTo(Status.CLOSED);
        assertThat(compute(Map.of(), 1, null, day(25)).status()).isEqualTo(Status.CLOSED);

        Signal over = compute(Map.of(day(14), new Daily(5, 4)), 2, null, day(18));
        assertThat(over.status()).isEqualTo(Status.OPEN); // 몰림 상태는 없다(ADR-0015)
        assertThat(over.ratio()).isEqualTo(2.0);
    }

    @Test
    void 정원_도달_예상일은_최근_3일_평균으로_외삽한다() {
        // 16·17·18일 지원 의사 1·0·1 → 하루 2/3 → 남은 1자리 = 1.5일 → 올림 2일 → 20일
        var daily = Map.of(day(16), new Daily(1, 1), day(18), new Daily(1, 1));
        assertThat(compute(daily, 3, null, day(18)).expectedFullOn()).isEqualTo(day(20));

        // 이미 닿았으면 null
        assertThat(compute(daily, 2, null, day(18)).expectedFullOn()).isNull();
        // 최근 3일 증가가 없으면 null
        assertThat(compute(Map.of(day(13), new Daily(1, 1)), 3, null, day(18)).expectedFullOn()).isNull();
        // 회차 종료일을 넘기면 null
        assertThat(compute(daily, 9, null, day(18)).expectedFullOn()).isNull();
        // 마감 전날을 넘기면 null(20일에 닿는데 20일 마감)
        assertThat(compute(daily, 3, day(20), day(18)).expectedFullOn()).isNull();
        assertThat(compute(daily, 3, day(21), day(18)).expectedFullOn()).isEqualTo(day(20));
        // 마감됐으면 null
        assertThat(compute(daily, 3, day(18), day(18)).expectedFullOn()).isNull();
    }

    @Test
    void 모집_첫날에는_첫날만으로_평균을_낸다() {
        // 13일 하루 2 → 하루 2 → 남은 2자리 = 1일 → 14일
        assertThat(compute(Map.of(day(13), new Daily(2, 2)), 4, null, day(13)).expectedFullOn()).isEqualTo(day(14));
    }

    private static Signal compute(Map<LocalDate, Daily> daily, int headcount, LocalDate closesOn, LocalDate asOf) {
        return SignalCalculator.compute(daily, headcount, closesOn, closesOn == null ? null : "APPLICATION_DEADLINE",
                false, START, END, asOf);
    }

    private static LocalDate day(int d) {
        return LocalDate.of(2026, 7, d);
    }
}
