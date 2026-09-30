package kr.ac.skuniv.coopradar.spike;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * E3 스파이크용 운영계획서 추출 결과(핵심 필드만, E1의 schema_lite.json과 같은 범위).
 * Anthropic Java SDK가 이 레코드에서 JSON 스키마를 만들어 structured outputs로 보낸다.
 *
 * 규칙: 모든 필드를 채우고(null 없음), 값이 없으면 "" / page 0.
 *
 * 선택형 값은 한글 enum 상수로 둔다. SDK의 스키마 생성기는 enum의 @JsonProperty를 무시하고
 * 상수 이름을 그대로 enum 값으로 보내기 때문에(2.66.0에서 확인), 모델이 보는 값과
 * 역직렬화 값이 같도록 상수 이름 자체를 문서 표기(공백·슬래시만 뺀 것)로 맞췄다.
 * 숫자·날짜 정규화와 검증은 모델이 아니라 코드가 한다.
 */
@JsonClassDescription("표준 현장실습학기제(Co-op) 운영 계획서 추출 결과")
public record OperationPlanLite(
        Institution institution,
        @JsonPropertyDescription("직무기술서 블록(부서명·직무명 한 묶음)마다 1개")
        List<Job> jobs,
        @JsonPropertyDescription("같은 문서 안에서 같은 항목이 서로 다르게 적힌 경우만. 없으면 빈 배열")
        List<Inconsistency> inconsistencies) {

    // ---------- 공통 ----------

    public record Text(
            @JsonPropertyDescription("문서에 적힌 값 그대로. 없으면 빈 문자열") String value,
            @JsonPropertyDescription("근거가 있는 PDF 쪽 번호(1부터). 없으면 0") long page,
            @JsonPropertyDescription("근거 원문 그대로(최대 80자). 없으면 빈 문자열") String quote) {
    }

    // ---------- 기관 ----------

    public record Institution(
            @JsonPropertyDescription("기관(법인)명") Text name,
            @JsonPropertyDescription("사업자등록번호") Text businessNo,
            @JsonPropertyDescription("개업년월일(적힌 그대로)") Text openedOn,
            @JsonPropertyDescription("한국표준산업분류코드. 쪽마다 다르면 첫 쪽 값") Text ksicCode,
            @JsonPropertyDescription("종업원 수(적힌 그대로)") Text employees,
            @JsonPropertyDescription("기관현황 규모 체크") Size size,
            @JsonPropertyDescription("상장여부 체크") Listing listing,
            @JsonPropertyDescription("전형방법") Text selectionMethod) {
    }

    public enum SizeValue {
        대기업,
        중견기업,
        중소기업,
        공공기관,
        협회기타,
        미기재,
        판독불가
    }

    public record Size(SizeValue value, long page, String quote) {
    }

    public enum ListingValue {
        코스피,
        코스닥,
        비상장,
        미기재,
        판독불가
    }

    public record Listing(ListingValue value, long page, String quote) {
    }

    // ---------- 직무 ----------

    public record Job(
            @JsonPropertyDescription("부서명") Text department,
            @JsonPropertyDescription("직무명") Text jobTitle,
            @JsonPropertyDescription("운영유형 체크") JobType jobType,
            @JsonPropertyDescription("실습기간(연도 오기가 있어도 적힌 그대로)") Text period,
            @JsonPropertyDescription("정규실습 시간(적힌 그대로)") Text hours,
            @JsonPropertyDescription("실습요일 중 체크된 요일") Weekdays weekdays,
            @JsonPropertyDescription("연장실습 여부 체크") Overtime overtime,
            @JsonPropertyDescription("별도 근로계약 체결 여부 체크") LaborContract laborContract,
            @JsonPropertyDescription("정규실습시간 실습지원비 금액(적힌 그대로)") Text stipendAmount,
            @JsonPropertyDescription("기타 지원 사항 중 체크된 항목") Benefits benefits,
            @JsonPropertyDescription("학생요건의 전공(인원) 칸 원문 전체") Text majorRequirement,
            @JsonPropertyDescription("모집 인원(적힌 그대로, 예: 1명)") Text headcount,
            @JsonPropertyDescription("학년 요건 원문") Text gradeRequirement,
            @JsonPropertyDescription("학점/평점 요건 원문") Text gpaRequirement,
            @JsonPropertyDescription("포트폴리오: '필수/제출'이면 필수, '우대'면 우대, 언급 없으면 언급 없음") Portfolio portfolio) {
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

    public enum Benefit {
        식사, 교통, 기숙사, 현물
    }

    public record Benefits(List<Benefit> value, long page, String quote) {
    }

    public enum PortfolioValue {
        필수,
        우대,
        언급없음
    }

    public record Portfolio(PortfolioValue value, long page, String quote) {
    }

    // ---------- 문서 내부 불일치 ----------

    public record Inconsistency(
            @JsonPropertyDescription("무엇과 무엇이 어떻게 다른지 한 문장") String description,
            long pageA, String quoteA, long pageB, String quoteB) {
    }
}
