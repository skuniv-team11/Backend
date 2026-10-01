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
     * @param zeroSignalJobs asOf까지 지원 의사가 0인 직무 수
     * @param closedJobs     asOf에 마감(CLOSED)인 직무 수
     */
    public record Summary(int jobs, int seats, int intentTotal, int zeroSignalJobs, int closedJobs) {
    }

    /**
     * @param eligiblePool   선호 전공 재학생 수(사람이 확정한 학과 매핑만, 전공 무관이면 전체 재학생)
     * @param alertCount     이 직무에 걸린 알림 + 기관 전체 알림 수
     * @param pastZeroRounds 지난 회차 0명 이력. 원소 모양은 지난 회차 결과를 적재할 때 정한다(지금은 빈 배열)
     */
    public record Row(int jobId, InstitutionRef institution, String title, int headcount, Signal signal,
                      int eligiblePool, List<Risk> risks, int alertCount, List<Object> pastZeroRounds) {
    }

    public record CenterBoard(LocalDate asOf, boolean isVirtual, Signal.Source signalSource, RoundRef round,
                              Summary summary, boolean historyAvailable, List<Row> rows, List<Alert> alerts) {
    }
}
