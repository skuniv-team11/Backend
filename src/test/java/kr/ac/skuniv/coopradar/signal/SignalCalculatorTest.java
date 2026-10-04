package kr.ac.skuniv.coopradar.signal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Map;
import kr.ac.skuniv.coopradar.signal.Signal.Status;
import org.junit.jupiter.api.Test;

/** 관심 신호 계산(docs/api/README.md 'Signal', ADR-0019). DB 없이 본다. 회차는 7/13~7/24. */
class SignalCalculatorTest {

    private static final LocalDate START = LocalDate.of(2026, 7, 13);
    private static final LocalDate END = LocalDate.of(2026, 7, 24);

    @Test
    void asOf_당일까지만_더하고_비율은_소수_둘째_자리() {
        var daily = Map.of(day(13), 1, day(15), 1, day(19), 1);
        Signal s = compute(daily, 3, null, day(18));
        assertThat(s.interest()).isEqualTo(2);
        assertThat(s.liveInterest()).isZero();
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

        Signal over = compute(Map.of(day(14), 4), 2, null, day(18));
        assertThat(over.status()).isEqualTo(Status.OPEN); // 몰림 상태는 없다(ADR-0015)
        assertThat(over.ratio()).isEqualTo(2.0);
    }

    @Test
    void 정원_도달_예상일은_최근_3일_평균으로_외삽한다() {
        // 16·17·18일 관심 1·0·1 → 하루 2/3 → 남은 1자리 = 1.5일 → 올림 2일 → 20일
        var daily = Map.of(day(16), 1, day(18), 1);
        assertThat(compute(daily, 3, null, day(18)).expectedFullOn()).isEqualTo(day(20));

        // 이미 닿았으면 null
        assertThat(compute(daily, 2, null, day(18)).expectedFullOn()).isNull();
        // 최근 3일 증가가 없으면 null
        assertThat(compute(Map.of(day(13), 1), 3, null, day(18)).expectedFullOn()).isNull();
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
        assertThat(compute(Map.of(day(13), 2), 4, null, day(13)).expectedFullOn()).isEqualTo(day(14));
    }

    @Test
    void 실제_담은_수는_기준일에_생긴_관심으로_더한다() {
        var daily = Map.of(day(15), 1);
        // 기준일 23일, 실제 2명 → 23일 이후 asOf에만 보인다
        Signal on = live(daily, 2, day(23), 4, null, day(23));
        assertThat(on.interest()).isEqualTo(3);
        assertThat(on.liveInterest()).isEqualTo(2);
        assertThat(on.ratio()).isEqualTo(0.75);
        assertThat(live(daily, 2, day(23), 4, null, day(24)).interest()).isEqualTo(3);
        Signal before = live(daily, 2, day(23), 4, null, day(22));
        assertThat(before.interest()).isEqualTo(1);
        assertThat(before.liveInterest()).isZero();
        // 원래 일별 값은 바꾸지 않는다
        assertThat(daily).containsExactlyEntriesOf(Map.of(day(15), 1));
    }

    @Test
    void 기준일에_마감된_직무에는_실제_담은_수를_더하지_않는다() {
        Signal closed = live(Map.of(day(15), 1), 3, day(23), 3, day(18), day(23));
        assertThat(closed.status()).isEqualTo(Status.CLOSED);
        assertThat(closed.interest()).isEqualTo(1);
        assertThat(closed.liveInterest()).isZero();
        // 기준일 다음 날 마감이면 더한다
        assertThat(live(Map.of(), 1, day(23), 3, day(24), day(23)).interest()).isEqualTo(1);
    }

    @Test
    void 실제_담은_수도_정원_도달_예상일_추세에_들어간다() {
        // 21·22·23일 가상 0·0·0 + 23일 실제 3 → 하루 1 → 남은 1자리 → 24일
        Signal s = live(Map.of(day(15), 1), 3, day(23), 5, null, day(23));
        assertThat(s.interest()).isEqualTo(4);
        assertThat(s.expectedFullOn()).isEqualTo(day(24));
    }

    private static Signal compute(Map<LocalDate, Integer> daily, int headcount, LocalDate closesOn, LocalDate asOf) {
        return SignalCalculator.compute(daily, headcount, closesOn, closesOn == null ? null : "APPLICATION_DEADLINE",
                false, START, END, asOf);
    }

    private static Signal live(Map<LocalDate, Integer> daily, int live, LocalDate liveOn, int headcount,
                               LocalDate closesOn, LocalDate asOf) {
        return SignalCalculator.compute(daily, live, liveOn, headcount, closesOn,
                closesOn == null ? null : "CENTER_CLOSED", false, START, END, asOf);
    }

    private static LocalDate day(int d) {
        return LocalDate.of(2026, 7, d);
    }
}
