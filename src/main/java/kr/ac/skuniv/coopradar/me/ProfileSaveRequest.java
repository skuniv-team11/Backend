package kr.ac.skuniv.coopradar.me;

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
 * {@code PUT /api/me/profile} 본문(계약 스키마 ProfileSaveRequest, docs/api me-profile.request.json).
 * 형식·범위는 여기서, 학과·사는 곳·자격증이 시드에 있는지와 동의는 {@link ProfileService}가 본다.
 * 메시지는 응답 {@code fields[].reason}으로 나간다(입력값은 넣지 않는다).
 *
 * @param certificates 가진 자격증 코드. null이면 답하지 않음, 빈 목록이면 없음(ADR-0021)
 * @param consent      true가 아니면(빠짐 포함) 400 CONSENT_REQUIRED
 */
public record ProfileSaveRequest(
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
        List<String> certificates,
        Boolean consent) {
}
