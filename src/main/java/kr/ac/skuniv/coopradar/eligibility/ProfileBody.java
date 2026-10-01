package kr.ac.skuniv.coopradar.eligibility;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/** {@code {"profile": {...}}}(계약 스키마 ProfileBody, docs/api profile-body.request.json). 오류 필드는 {@code profile.gpa}처럼 나간다. */
public record ProfileBody(@NotNull(message = "필수예요") @Valid ProfileInput profile) {
}
