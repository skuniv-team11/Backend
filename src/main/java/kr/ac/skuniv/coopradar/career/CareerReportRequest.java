package kr.ac.skuniv.coopradar.career;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /api/me/career-report} 본문(계약 스키마 CareerReportRequest, docs/api career-report.request.json).
 *
 * @param practiceText 수행결과보고서 '실습 내용'(100~3,000자)
 * @param consent      실습 내용을 AI에 보내고 리포트와 함께 저장하는 데 동의. true가 아니면 400 CONSENT_REQUIRED
 */
public record CareerReportRequest(
        @NotNull(message = "필수예요") Integer jobId,
        @NotNull(message = "필수예요") @Size(min = 100, max = 3000, message = "100~3,000자") String practiceText,
        Boolean consent) {
}
