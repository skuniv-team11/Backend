package kr.ac.skuniv.coopradar.signal;

import java.time.LocalDate;

/**
 * 모집 신호(계약 스키마 Signal, docs/api/README.md 'Signal'). 지망 점검(#23)과 센터 현황판(#24)이 같은 계산을 쓴다.
 * 관심 = 내 지망에 담은 사람 수(ADR-0019). 모집기간 리플레이 가상 값에 실제 사용자가 담은 수를 더한 값이다 —
 * 이 값을 주는 응답에는 isVirtual·signalSource를 함께 넣는다.
 *
 * @param interest       asOf까지 누적한 관심 수(가상 + 실제)
 * @param liveInterest   interest 중 실제 사용자가 담은 수. asOf가 기준일(replay.defaultAsOf)보다 앞이거나 기준일에 마감된 직무면 0
 * @param ratio          interest ÷ headcount, 소수 둘째 자리 반올림
 * @param status         OPEN · CLOSED. 관심이 정원을 넘어도 몰림 상태는 없다(ADR-0015)
 * @param expectedFullOn 정원 도달 예상일. 모집기간 안에 닿지 않거나 이미 닿았으면 null
 */
public record Signal(
        int interest,
        int liveInterest,
        int headcount,
        double ratio,
        Status status,
        LocalDate closesOn,
        String closeReason,
        boolean closesOnIsVirtual,
        LocalDate expectedFullOn) {

    public enum Status { OPEN, CLOSED }

    /** 응답의 signalSource. 지금은 REPLAY(리플레이 가상 값 + 실제 담은 수)뿐이다. */
    public enum Source { REPLAY, LIVE }
}
