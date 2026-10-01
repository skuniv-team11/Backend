package kr.ac.skuniv.coopradar.me;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 저장한 프로필(계약 스키마 SavedProfile, docs/api me-profile.json). {@link ProfileView}에 동의·수정 시각을 더한 모양이다.
 * 필드 이름이 JSON 이름이다.
 */
public record SavedProfile(
        int departmentId,
        int grade,
        int completedSemesters,
        BigDecimal gpa,
        boolean graduationExpected,
        String interestText,
        String homeAreaCode,
        ProfileView.DepartmentRef department,
        ProfileView.AreaView homeArea,
        boolean isExample,
        OffsetDateTime consentedAt,
        OffsetDateTime updatedAt) {

    /** 체험 계정 응답(#5)에 쓰는 모양. 시각은 빠진다. */
    ProfileView toView() {
        return new ProfileView(departmentId, grade, completedSemesters, gpa, graduationExpected, interestText, homeAreaCode,
                department, homeArea, isExample);
    }
}
