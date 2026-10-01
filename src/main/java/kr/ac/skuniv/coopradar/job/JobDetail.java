package kr.ac.skuniv.coopradar.job;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 직무 상세(docs/api #17, 계약 스키마 JobDetail, job-detail.json). 필드 이름이 JSON 이름이다.
 * 코드값은 영문 대문자 그대로 주고 화면 표기는 {@code GET /api/codes}가 맡는다. 통근 시간은 여기 없다(#18을 따로 부른다).
 */
public record JobDetail(
        int id,
        RoundRef round,
        Institution institution,
        String team,
        String title,
        String overview,
        String educationGoal,
        String competencies,
        List<WeeklyPlan> weeklyPlan,
        Conditions conditions,
        Requirements requirements,
        Workplace workplace,
        Closing closing,
        List<Evidence> evidence,
        List<Alert> alerts,
        List<SeniorNote> seniorNotes) {

    /** 사업자번호·대표자명은 두지 않는다(ADR-0004). */
    public record Institution(int id, String name, String size, String listing, String businessType,
                              String businessItem, String address, String ntsStatus, LocalDate ntsCheckedOn) {
    }

    public record WeeklyPlan(int seq, String weeksLabel, String content) {
    }

    public record Period(LocalDate start, LocalDate end) {
    }

    public record Conditions(String course, String jobType, Period period, String workHoursText, BigDecimal weeklyHours,
                             List<String> weekdays, String overtime, Boolean laborContract, Stipend stipend,
                             List<String> benefits, int headcount) {
    }

    /**
     * majorText는 표시용 원문이다. 판정은 job_major_alias로 한다.
     *
     * @param majorAliases 선호 전공 표기마다 사람이 확정한 학과 대응(표기 id 순, 학과 id 순). 전공 무관이면 []
     */
    public record Requirements(String gradeRule, BigDecimal gpaMin, String portfolio, String certificate,
                               String certificateText, String majorText, boolean majorOpen,
                               List<MajorAlias> majorAliases) {

        Requirements withMajorAliases(List<MajorAlias> aliases) {
            return new Requirements(gradeRule, gpaMin, portfolio, certificate, certificateText, majorText, majorOpen,
                    majorOpen ? List.of() : aliases);
        }
    }

    /** 선호 전공 표기 하나와 확정된 학과들. 확정 전 표기(중어전공)는 departments가 비어 있다. */
    public record MajorAlias(String label, List<DepartmentRef> departments) {
    }

    public record DepartmentRef(int id, String name) {
    }

    /** hasCoordinates가 true면 프론트가 통근 조회(#18)를 부른다. */
    public record Workplace(String address, boolean hasCoordinates) {
    }

    public record Closing(LocalDate closesOn, String closeReason, boolean closesOnIsVirtual) {
    }

    /** AI가 운영계획서에서 뽑은 값과 근거 쪽·인용문. 원문 PDF 링크는 주지 않는다. */
    public record Evidence(String fieldKey, String label, String rawValue, String documentTitle, int page, String quote) {
    }

    /** 같은 기관의 선배 수기. 이름·학과·학년은 없다(시드에도 없다). */
    public record SeniorNote(String termCode, String teamText, String documentTitle, int page, List<String> activities) {
    }
}
