package kr.ac.skuniv.coopradar.center;

import java.time.LocalDate;
import java.util.List;
import kr.ac.skuniv.coopradar.job.Alert;
import kr.ac.skuniv.coopradar.job.InstitutionRef;
import kr.ac.skuniv.coopradar.job.RoundRef;
import kr.ac.skuniv.coopradar.job.JobDetail.Closing;

/** 센터 현황판 응답 모양(docs/api #24, 계약 스키마 CenterBoard, center-board.json). 필드 이름이 JSON 이름이다. */
public final class CenterDtos {

    private CenterDtos() {
    }

    public enum RiskCode { NARROW_POOL, PORTFOLIO_REQUIRED, CERTIFICATE_REQUIRED, WEEKEND, DOC_ALERT }

    public record Risk(RiskCode code, String label, String detail) {
    }

    /** @param closedJobs asOf(모집 판정 기준일)에 마감인 직무 수 */
    public record Summary(int jobs, int seats, int closedJobs) {
    }

    /**
     * @param closing      모집마감(직무 상세·판정 목록과 같은 값)
     * @param closed       asOf에 마감인지(ADR-0035의 마감 규칙)
     * @param eligiblePool 선호 전공 재학생 수(사람이 확정한 학과 매핑만, 전공 무관이면 전체 재학생)
     * @param alertCount   이 직무에 걸린 알림 + 기관 전체 알림 수
     */
    public record Row(int jobId, InstitutionRef institution, String title, int headcount, Closing closing,
                      boolean closed, int eligiblePool, List<Risk> risks, int alertCount, int views) {
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

    /** @param asOf 마감을 본 날(모집 판정 기준일, ADR-0035) */
    public record CenterBoard(LocalDate asOf, RoundRef round, Summary summary, List<Row> rows, List<Alert> alerts,
                              Todo todo, Demand demand) {
    }
}
