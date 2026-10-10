package kr.ac.skuniv.coopradar.internship;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;

/**
 * 지원서 임시 저장 본문(#38, docs/api me-application.request.json). 칸은 비워도 저장된다 — 빠진 칸은 낼 때(#41) 본다.
 * 수집·이용 동의(consents.collect)가 true가 아니면 아무것도 저장하지 않는다(400 CONSENT_REQUIRED).
 * 1~3지망은 이 본문이 아니라 담은 직무 순위(#22)에서 온다.
 */
public record ApplicationRequest(
        @Valid ApplicantInput applicant,
        @Valid ResumeInput resume,
        @Size(min = 4, max = 4, message = "4문항")
        List<@Size(max = 3000, message = "3,000자 이하") String> essays,
        Boolean pledge,
        @NotNull(message = "필수예요") @Valid ConsentsInput consents,
        @Size(max = 30, message = "30자 이하") String signature) {

    public record ApplicantInput(
            @Size(max = 30, message = "30자 이하") String nameKo,
            @Size(max = 60, message = "60자 이하") String nameEn,
            LocalDate birthDate,
            @Pattern(regexp = "^[MF]$", message = "M 또는 F") String gender,
            @Pattern(regexp = "^[0-9][0-9-]{7,18}$", message = "숫자와 -만") String phone,
            @Size(max = 254, message = "254자 이하") String email,
            @Size(max = 200, message = "200자 이하") String address,
            @Pattern(regexp = "^[0-9]{6,12}$", message = "숫자 6~12자리") String studentNo,
            @Size(max = 60, message = "60자 이하") String minorMajor) {
    }

    public record ResumeInput(
            @Size(max = 10, message = "10줄 이하") List<@Valid CertificateInput> certificates,
            @Size(max = 10, message = "10줄 이하") List<@Valid AwardInput> awards,
            @Size(max = 10, message = "10줄 이하") List<@Valid CareerInput> careers) {
    }

    public record CertificateInput(
            @Pattern(regexp = "^(CERTIFICATE|LANGUAGE|EDUCATION)$", message = "CERTIFICATE·LANGUAGE·EDUCATION") String kind,
            @Size(max = 60, message = "60자 이하") String name,
            @Size(max = 60, message = "60자 이하") String issuer,
            @Size(max = 20, message = "20자 이하") String acquiredOn) {
    }

    public record AwardInput(
            @Size(max = 60, message = "60자 이하") String name,
            @Size(max = 60, message = "60자 이하") String issuer,
            @Size(max = 20, message = "20자 이하") String awardedOn,
            @Size(max = 100, message = "100자 이하") String detail) {
    }

    public record CareerInput(
            @Size(max = 40, message = "40자 이하") String period,
            @Size(max = 60, message = "60자 이하") String company,
            @Size(max = 60, message = "60자 이하") String role,
            @Size(max = 100, message = "100자 이하") String note) {
    }

    public record ConsentsInput(Boolean collect, Boolean thirdParty) {
    }
}
