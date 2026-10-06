package kr.ac.skuniv.coopradar.eligibility;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/**
 * 요청 본문의 학생 프로필(계약 스키마 Profile). 판정·추천·지망 점검이 본문으로 받는다 — 저장하지 않는다(ADR-0008).
 * 형식·범위는 여기서, 학과가 시드에 있는지는 서비스가 본다. 메시지는 응답 {@code fields[].reason}으로 나간다(입력값은 넣지 않는다).
 *
 * @param homeAreaCode 통근 조회에만 쓴다. 판정·추천은 읽지 않으므로 형식만 본다
 * @param certificates 가진 자격증 코드(GET /api/certificates). null이면 답하지 않음, 빈 목록이면 없음(ADR-0021).
 *                     코드가 코드표에 있는지는 서비스가 본다
 */
public record ProfileInput(
        @NotNull(message = "필수예요")
        Integer departmentId,
        @NotNull(message = "필수예요") @Min(value = 1, message = "1~4") @Max(value = 4, message = "1~4")
        Integer grade,
        @NotNull(message = "필수예요") @Min(value = 0, message = "0~8") @Max(value = 8, message = "0~8")
        Integer completedSemesters,
        @NotNull(message = "필수예요")
        @DecimalMin(value = "0.0", message = "0.0~4.5, 소수 첫째 자리까지")
        @DecimalMax(value = "4.5", message = "0.0~4.5, 소수 첫째 자리까지")
        @Digits(integer = 1, fraction = 1, message = "0.0~4.5, 소수 첫째 자리까지")
        BigDecimal gpa,
        @NotNull(message = "필수예요")
        Boolean graduationExpected,
        @Size(max = 200, message = "200자 이하")
        String interestText,
        @Pattern(regexp = "^(11|28|41)\\d{3}$", message = "GET /api/areas의 code")
        String homeAreaCode,
        @Size(max = 20, message = "20개 이하")
        List<String> certificates) {
}
