package kr.ac.skuniv.coopradar.me;

import java.math.BigDecimal;
import java.util.List;

/**
 * 저장된 프로필을 화면에 돌려주는 모양(계약 스키마 ProfileView). 체험 계정 응답에 쓴다.
 * 필드 이름이 JSON 이름이다.
 *
 * @param certificates 가진 자격증 코드(코드표 순서). null이면 답하지 않음, 빈 목록이면 없음(ADR-0021)
 */
public record ProfileView(
        int departmentId,
        int grade,
        int completedSemesters,
        BigDecimal gpa,
        boolean graduationExpected,
        String interestText,
        String homeAreaCode,
        List<String> certificates,
        DepartmentRef department,
        AreaView homeArea,
        boolean isExample) {

    public record DepartmentRef(int id, String name) {
    }

    public record AreaView(String code, String sido, String name) {
    }
}
