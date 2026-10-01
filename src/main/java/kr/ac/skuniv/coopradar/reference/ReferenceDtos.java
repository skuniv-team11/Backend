package kr.ac.skuniv.coopradar.reference;

import java.time.LocalDate;
import java.util.List;

/** 기준 정보 API의 응답 모양(docs/api: departments.json, areas.json, rounds-current.json). 필드 이름이 JSON 이름이다. */
public final class ReferenceDtos {

    private ReferenceDtos() {
    }

    public record Department(int id, String name, String college) {
    }

    public record Departments(List<Department> departments) {
    }

    public record Area(String code, String sido, String name) {
    }

    public record Areas(List<Area> areas) {
    }

    /**
     * 현재 모집 회차(#13). {@code replay}는 S5·C4 날짜 슬라이더 범위와 기본 기준일이다.
     * 판정·지망 점검·현황판의 asOf 범위 검사도 이 값을 쓴다(README '지망' — asOf는 회차 기간 안).
     */
    public record CurrentRound(int id, String programName, String termCode, int roundNo, LocalDate recruitStart,
                               LocalDate recruitEnd, Replay replay) {
    }

    /**
     * @param signalsAreVirtual 모집 신호가 리플레이용 가상 데이터인지. MVP는 항상 true(실제 신호를 모으지 않는다)
     */
    public record Replay(LocalDate defaultAsOf, LocalDate minDate, LocalDate maxDate, boolean signalsAreVirtual) {
    }
}
