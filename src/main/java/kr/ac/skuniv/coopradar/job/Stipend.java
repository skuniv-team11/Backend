package kr.ac.skuniv.coopradar.job;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 실습지원비(계약 스키마 Stipend, docs/api/README.md 'Stipend'). 직무 상세·추천 카드가 같이 쓴다.
 *
 * @param basis        MONTHLY(월액) · HOURLY(시급) · UNSPECIFIED
 * @param amount       원. 없으면 null
 * @param minWageRatio 2026 최저임금 대비 %, 소수 첫째 자리(반올림). 금액이나 기준이 없으면 null
 */
public record Stipend(String basis, Integer amount, BigDecimal minWageRatio) {

    /** 2026 최저임금 월 환산액(시급 × 209시간). docs/api/README.md와 같은 값. */
    static final BigDecimal MIN_WAGE_MONTHLY_2026 = BigDecimal.valueOf(2_156_880);
    /** 2026 최저임금 시급. */
    static final BigDecimal MIN_WAGE_HOURLY_2026 = BigDecimal.valueOf(10_320);

    public static Stipend of(String basis, Integer amount) {
        BigDecimal base = switch (basis) {
            case "MONTHLY" -> MIN_WAGE_MONTHLY_2026;
            case "HOURLY" -> MIN_WAGE_HOURLY_2026;
            default -> null;
        };
        BigDecimal ratio = base == null || amount == null ? null
                : BigDecimal.valueOf(amount).multiply(BigDecimal.valueOf(100)).divide(base, 1, RoundingMode.HALF_UP);
        return new Stipend(basis, amount, ratio);
    }
}
