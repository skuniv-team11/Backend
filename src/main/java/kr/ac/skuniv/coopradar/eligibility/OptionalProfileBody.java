package kr.ac.skuniv.coopradar.eligibility;

import jakarta.validation.Valid;

/**
 * {@code {"profile": {...}}} 또는 {@code {}}(계약 스키마 OptionalProfileBody). profile을 빼면 저장한 프로필로 본다 —
 * 둘 다 없으면 404 PROFILE_NOT_FOUND(ADR-0035). 판정(#14)·탐색 카드(#28)가 쓴다.
 */
public record OptionalProfileBody(@Valid ProfileInput profile) {
}
