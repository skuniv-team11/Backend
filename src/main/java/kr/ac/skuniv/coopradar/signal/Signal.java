package kr.ac.skuniv.coopradar.signal;

import java.time.LocalDate;

/**
 * 모집 신호(계약 스키마 Signal, docs/api/README.md 'Signal'). 지망 점검(#23)과 센터 현황판(#24)이 같은 계산을 쓴다.
 * 모집기간 리플레이용 가상 데이터다 — 이 값을 주는 응답에는 isVirtual·signalSource를 함께 넣는다.
 *
 * @param intent         asOf까지 누적한 지원 의사 수
 * @param interest       asOf까지 누적한 관심 수
 * @param ratio          intent ÷ headcount, 소수 둘째 자리 반올림
 * @param status         OPEN · CLOSED. 지원 의사가 정원을 넘어도 몰림 상태는 없다(ADR-0015)
 * @param expectedFullOn 정원 도달 예상일. 모집기간 안에 닿지 않거나 이미 닿았으면 null
 */
public record Signal(
        int intent,
        int interest,
        int headcount,
        double ratio,
        Status status,
        LocalDate closesOn,
        String closeReason,
        boolean closesOnIsVirtual,
        LocalDate expectedFullOn) {

    public enum Status { OPEN, CLOSED }

    /** 응답의 signalSource. MVP는 리플레이뿐이다. */
    public enum Source { REPLAY, LIVE }
}
