package kr.ac.skuniv.coopradar.me;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * [예시 프로필로 시작]이 저장하는 시연용 학생(application.yml {@code app.example-profile}). 실제 사람이 아니다.
 * 학과는 이름으로 department 시드에서 찾는다. 사는 곳은 area 시드에 없으면 비운다(사는 곳 목록은 행정표준코드 최신본으로 시드, ADR-0007).
 *
 * @param certificates 가진 자격증 코드. 설정이 없으면 빈 목록(자격증 없음 — 필수 자격증 직무가 지원 불가로 보인다, ADR-0021).
 *                     자격증 코드표에 없는 코드는 저장하지 않는다
 */
@ConfigurationProperties("app.example-profile")
public record ExampleProfileProperties(
        String departmentName,
        int grade,
        int completedSemesters,
        BigDecimal gpa,
        boolean graduationExpected,
        String interestText,
        String homeAreaCode,
        List<String> certificates) {

    public ExampleProfileProperties {
        certificates = certificates == null ? List.of() : List.copyOf(certificates);
    }
}
