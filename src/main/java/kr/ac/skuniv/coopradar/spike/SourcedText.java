package kr.ac.skuniv.coopradar.spike;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * 값 하나와 그 근거(쪽 번호·원문 인용). 두 추출 파트가 함께 쓴다.
 *
 * 이 레코드가 스키마에서 열세 번 넘게 반복되므로, SDK가 이것을 $defs 로 묶어 주는지가
 * 문법 크기 한도를 넘지 않는 조건이다(ADR-0003). OperationPlanRequestsTest 가 확인한다.
 */
public record SourcedText(
        @JsonPropertyDescription("문서에 적힌 값 그대로. 없으면 빈 문자열") String value,
        @JsonPropertyDescription("근거가 있는 PDF 쪽 번호(1부터). 없으면 0") long page,
        @JsonPropertyDescription("근거 원문 그대로(최대 80자). 없으면 빈 문자열") String quote) {
}
