package kr.ac.skuniv.coopradar.spike;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * 운영계획서 추출 1/2 — 기관 현황과 문서 내부 불일치.
 *
 * PDF 1건을 호출 2번으로 나눈다(ADR-0003 '문법 크기 한도'). 기관·직무·불일치를 한 스키마에 담으면
 * 문법이 한도를 넘어 400 "The compiled grammar is too large" 가 난다. 직무는
 * {@link OperationPlanJobsPart} 가 맡는다.
 *
 * 규칙: 모든 필드를 채우고(null 없음), 값이 없으면 "" / page 0 / 선택형은 미기재.
 * 선택형은 한글 enum 상수명을 쓰고 @JsonProperty를 붙이지 않는다(ADR-0003, StructuredOutputRulesTest).
 */
@JsonClassDescription("표준 현장실습학기제(Co-op) 운영 계획서 — 기관 현황과 문서 내부 불일치")
public record OperationPlanInstitutionPart(
        Institution institution,
        @JsonPropertyDescription("같은 문서 안에서 같은 항목이 서로 다르게 적힌 경우만. 없으면 빈 배열")
        List<Inconsistency> inconsistencies) {

    public record Institution(
            @JsonPropertyDescription("기관(법인)명") SourcedText name,
            @JsonPropertyDescription("사업자등록번호") SourcedText businessNo,
            @JsonPropertyDescription("개업년월일(적힌 그대로)") SourcedText openedOn,
            @JsonPropertyDescription("한국표준산업분류코드. 쪽마다 다르면 첫 쪽 값을 넣고 inconsistencies에 기록")
            SourcedText ksicCode,
            @JsonPropertyDescription("종업원 수(적힌 그대로)") SourcedText employees,
            @JsonPropertyDescription("매출액(적힌 그대로)") SourcedText revenue,
            @JsonPropertyDescription("사업장 소재지") SourcedText address,
            @JsonPropertyDescription("홈페이지") SourcedText homepage,
            @JsonPropertyDescription("기관현황 규모 체크") Size size,
            @JsonPropertyDescription("상장여부 체크") Listing listing,
            @JsonPropertyDescription("사업의 종류(업태)") SourcedText businessType,
            @JsonPropertyDescription("사업의 종류(종목)") SourcedText businessItem,
            @JsonPropertyDescription("정규 근로시간 1일 기준 시간") SourcedText dailyHours,
            @JsonPropertyDescription("정규 근로시간 1주 기준 시간") SourcedText weeklyHours,
            @JsonPropertyDescription("정규 근로일수(주 N일)") SourcedText weeklyDays,
            @JsonPropertyDescription("근로요일(예: 월~금)") SourcedText workDaysText,
            @JsonPropertyDescription("전형방법") SourcedText selectionMethod,
            @JsonPropertyDescription("접수마감일자(일정별도협의 표시 포함, 적힌 그대로)") SourcedText applicationDeadline,
            @JsonPropertyDescription("면접일자(적힌 그대로)") SourcedText interviewDate,
            @JsonPropertyDescription("최종선발일자(적힌 그대로)") SourcedText finalSelectionDate) {
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

    public record Inconsistency(
            @JsonPropertyDescription("무엇과 무엇이 어떻게 다른지 한 문장") String description,
            long pageA, String quoteA, long pageB, String quoteB) {
    }
}
