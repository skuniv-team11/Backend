package kr.ac.skuniv.coopradar.job;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import kr.ac.skuniv.coopradar.eligibility.EligibilityDtos.MyEligibility;

/**
 * 직무 상세(docs/api #17, 계약 스키마 JobDetail, job-detail.json). 필드 이름이 JSON 이름이다.
 * 코드값은 영문 대문자 그대로 주고 화면 표기는 {@code GET /api/codes}가 맡는다. 통근 시간은 여기 없다(#18을 따로 부른다).
 * 조회 수·내 담기·지망 순위·저장한 프로필로 본 판정(myEligibility)을 함께 준다 — 상세 화면이 #25·#14를 따로 부르지 않게(ADR-0035).
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
        int views,
        int todayViews,
        boolean planned,
        Integer planRank,
        MyEligibility myEligibility,
        List<Evidence> evidence,
        List<Alert> alerts,
        List<SeniorNote> seniorNotes,
        List<Photo> photos) {

    /** 사업자번호·대표자명은 두지 않는다(ADR-0004). {@code logoPath}는 {@link InstitutionRef}와 같다(ADR-0019). */
    public record Institution(int id, String name, String logoPath, String size, String listing, String businessType,
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

    /** 선호 전공 표기 하나와 확정된 학과들. 확정 전 표기(DRAFT)는 departments가 비어 있다. */
    public record MajorAlias(String label, List<DepartmentRef> departments) {
    }

    public record DepartmentRef(int id, String name) {
    }

    /**
     * hasCoordinates가 true면 프론트가 통근 조회(#18)를 부른다. 근로지 주소가 있으면 true다 — 좌표는 DB에 없고
     * 통근 조회 때 카카오 주소 검색으로 구한다(ADR-0007). 이름은 계약을 바꾸지 않으려고 그대로 둔다.
     */
    public record Workplace(String address, boolean hasCoordinates) {
    }

    public record Closing(LocalDate closesOn, String closeReason, boolean closesOnIsVirtual) {
    }

    /** AI가 운영계획서에서 뽑은 값과 근거 쪽·인용문. 원문 PDF 링크는 주지 않는다. */
    public record Evidence(String fieldKey, String label, String rawValue, String documentTitle, int page, String quote) {
    }

    /**
     * 같은 기관의 선배 수기 전문(ADR-0030). 이름·사진은 없다(시드에도 없다). '우수' 수기라 긍정 쪽으로 치우쳐 있다 — 화면이 밝힌다.
     *
     * @param outcomes 실습 결과 중 원문 그대로 자른 사실 구절(만든 결과물·맡은 일 등, 0~3개). 추천 근거용(ADR-0020)
     * @param results  실습 결과 문단 전문. 수기에 없으면 null
     */
    public record SeniorNote(String termCode, String teamText, String documentTitle, int page, String major, String grade,
                             String oneLine, String companyIntro, List<String> activities, List<String> outcomes,
                             String results, String reflection) {
    }

    /**
     * 실습기관 소개서의 '회사 전경 및 활동사진'(ADR-0030). {@code path}는 로고처럼 API 서버의 정적 경로다(예: /photos/7/1.jpg).
     *
     * @param caption 사진 아래에 인쇄된 설명 원문. 없으면 null
     */
    public record Photo(int seq, String path, String caption, String documentTitle, int page, int width, int height) {
    }
}
