package kr.ac.skuniv.coopradar.me;

import java.math.BigDecimal;

/**
 * 저장된 프로필을 화면에 돌려주는 모양(계약 스키마 ProfileView). 체험 계정 응답에 쓴다.
 * 필드 이름이 JSON 이름이다.
 */
public record ProfileView(
        int departmentId,
        int grade,
        int completedSemesters,
        BigDecimal gpa,
        boolean graduationExpected,
        String interestText,
        String homeAreaCode,
        DepartmentRef department,
        AreaView homeArea,
        boolean isExample) {

    public record DepartmentRef(int id, String name) {
    }

    public record AreaView(String code, String sido, String name) {
    }
}
