package kr.ac.skuniv.coopradar.spike;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * 운영계획서 추출 2/2 — [붙임1] 운영 계획 및 직무기술서의 직무.
 *
 * 기관 현황과 문서 내부 불일치는 {@link OperationPlanInstitutionPart} 가 맡는다. 나누는 이유는
 * 그쪽 주석과 ADR-0003 참고.
 *
 * 규칙: 모든 필드를 채우고(null 없음), 값이 없으면 "" / page 0 / 선택형은 미기재.
 * 선택형은 한글 enum 상수명을 쓰고 @JsonProperty를 붙이지 않는다(ADR-0003, StructuredOutputRulesTest).
 */
@JsonClassDescription("표준 현장실습학기제(Co-op) 운영 계획서 — 직무")
public record OperationPlanJobsPart(
        @JsonPropertyDescription("직무 1개 = 팀 1개. 직무기술서 블록의 부서명 칸에 팀이 여러 개 적혀 있으면 팀마다 1개(시스템 프롬프트 '직무(jobs) 나누기' 참고)")
        List<Job> jobs) {

    public record Job(
            @JsonPropertyDescription("부서명") SourcedText department,
            @JsonPropertyDescription("직무명") SourcedText jobTitle,
            @JsonPropertyDescription("직무기술서의 주소(근무지)") SourcedText workAddress,
            @JsonPropertyDescription("운영과정 체크") Course course,
            @JsonPropertyDescription("운영유형 체크") JobType jobType,
            @JsonPropertyDescription("실습기간(연도 오기가 있어도 적힌 그대로)") SourcedText period,
            @JsonPropertyDescription("정규실습 시간(적힌 그대로)") SourcedText hours,
            @JsonPropertyDescription("실습요일 중 체크된 요일") Weekdays weekdays,
            @JsonPropertyDescription("연장실습 여부 체크") Overtime overtime,
            @JsonPropertyDescription("별도 근로계약 체결 여부 체크") LaborContract laborContract,
            @JsonPropertyDescription("정규실습시간 실습지원비 지급기준") StipendBasis stipendBasis,
            @JsonPropertyDescription("정규실습시간 실습지원비 금액(적힌 그대로, 예: 1,620,000)") SourcedText stipendAmount,
            @JsonPropertyDescription("연장실습시간 지원비(적힌 그대로)") SourcedText overtimePay,
            @JsonPropertyDescription("지급예정일(당월/익월 N일, 적힌 그대로)") SourcedText payDay,
            @JsonPropertyDescription("기타 지원 사항 중 체크된 항목") Benefits benefits,
            @JsonPropertyDescription("교육목표 원문") SourcedText educationGoal,
            @JsonPropertyDescription("직무개요 원문") SourcedText jobOverview,
            @JsonPropertyDescription("운영/지도 계획의 주차별 항목. 없으면 빈 배열") List<WeeklyPlanItem> weeklyPlan,
            @JsonPropertyDescription("학생요건의 전공(인원) 칸 원문 전체") SourcedText majorRequirement,
            @JsonPropertyDescription("모집 인원(전공(인원) 칸 등에 적힌 그대로, 예: 1명)") SourcedText headcount,
            @JsonPropertyDescription("학년 요건 원문") SourcedText gradeRequirement,
            @JsonPropertyDescription("학점/평점 요건 원문") SourcedText gpaRequirement,
            @JsonPropertyDescription("요구역량 원문") SourcedText competencies,
            @JsonPropertyDescription("기타사항 원문") SourcedText notes,
            @JsonPropertyDescription("포트폴리오: '필수/제출'이면 필수, '우대'면 우대, 언급 없으면 언급없음") Requirement portfolio,
            @JsonPropertyDescription("자격증: '필수'면 필수, '우대'면 우대, 언급 없으면 언급없음") Requirement certificate) {
    }

    public record WeeklyPlanItem(
            @JsonPropertyDescription("주차 표기(예: 1~2주차)") String weeks,
            @JsonPropertyDescription("그 주차의 계획 내용 원문") String content) {
    }

    public enum CourseValue {
        방학과정,
        학기과정,
        방학학기연계과정,
        미기재,
        판독불가
    }

    public record Course(CourseValue value, long page, String quote) {
    }

    public enum JobTypeValue {
        직무체험형,
        채용연계형,
        미기재,
        판독불가
    }

    public record JobType(JobTypeValue value, long page, String quote) {
    }

    public enum Weekday {
        월, 화, 수, 목,
        금, 토, 일
    }

    public record Weekdays(List<Weekday> value, long page, String quote) {
    }

    public enum OvertimeValue {
        없음,
        상황별실시,
        주기적상시적실시,
        미기재,
        판독불가
    }

    public record Overtime(OvertimeValue value, long page, String quote) {
    }

    public enum YesNo {
        Y,
        N,
        미기재,
        판독불가
    }

    public record LaborContract(YesNo value, long page, String quote) {
    }

    public enum StipendBasisValue {
        월기준,
        시간기준,
        미기재,
        판독불가
    }

    public record StipendBasis(StipendBasisValue value, long page, String quote) {
    }

    public enum Benefit {
        식사, 교통, 기숙사, 현물
    }

    public record Benefits(List<Benefit> value, long page, String quote) {
    }

    /** 포트폴리오·자격증이 같은 값 집합을 쓴다. 한 레코드로 두면 스키마에서도 $defs 하나로 묶인다. */
    public enum RequirementValue {
        필수,
        우대,
        언급없음
    }

    public record Requirement(RequirementValue value, long page, String quote) {
    }
}
