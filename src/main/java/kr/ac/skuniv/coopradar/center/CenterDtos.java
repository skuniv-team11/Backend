package kr.ac.skuniv.coopradar.center;

import java.time.LocalDate;
import java.util.List;
import kr.ac.skuniv.coopradar.job.Alert;
import kr.ac.skuniv.coopradar.job.InstitutionRef;
import kr.ac.skuniv.coopradar.job.RoundRef;
import kr.ac.skuniv.coopradar.signal.Signal;

/** 센터 현황판 응답 모양(docs/api #24, 계약 스키마 CenterBoard, center-board.json). 필드 이름이 JSON 이름이다. */
public final class CenterDtos {

    private CenterDtos() {
    }

    public enum RiskCode { NARROW_POOL, PORTFOLIO_REQUIRED, CERTIFICATE_REQUIRED, WEEKEND, DOC_ALERT }

    public record Risk(RiskCode code, String label, String detail) {
    }

    /**
     * @param interestTotal     asOf까지 관심(담은 사람) 합. 가상 + 실제
     * @param liveInterestTotal interestTotal 중 실제 사용자가 담은 수
     * @param zeroSignalJobs    asOf까지 관심이 0인 직무 수
     * @param closedJobs        asOf에 마감(CLOSED)인 직무 수
     */
    public record Summary(int jobs, int seats, int interestTotal, int liveInterestTotal, int zeroSignalJobs,
                          int closedJobs) {
    }

    /**
     * @param eligiblePool   선호 전공 재학생 수(사람이 확정한 학과 매핑만, 전공 무관이면 전체 재학생)
     * @param alertCount     이 직무에 걸린 알림 + 기관 전체 알림 수
     * @param pastZeroRounds 지난 회차 0명 이력. 원소 모양은 지난 회차 결과를 적재할 때 정한다(지금은 빈 배열)
     */
    public record Row(int jobId, InstitutionRef institution, String title, int headcount, Signal signal,
                      int eligiblePool, List<Risk> risks, int alertCount, List<Object> pastZeroRounds, int views) {
    }

    /**
     * 처리할 것(ADR-0033).
     *
     * @param counselPending 상담 확정 대기. 상담 기능 전이라 null
     */
    public record Todo(int newApplications, int fixRequested, Integer counselPending) {
    }

    /** 학생이 찾는 직무(직무 탐색 1~3위의 NCS 세분류) vs 이번 회차 공고. */
    public record DemandRow(String ncsCode, String ncsName, int students, int jobs, int seats) {
    }

    /** @param explorers 범위 안에서 직무 탐색 결과가 있는 학생 수 */
    public record Demand(int explorers, List<DemandRow> rows) {
    }

    public record CenterBoard(LocalDate asOf, boolean isVirtual, Signal.Source signalSource, RoundRef round,
                              Summary summary, boolean historyAvailable, List<Row> rows, List<Alert> alerts, Todo todo,
                              Demand demand) {
    }
}
