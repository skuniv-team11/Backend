package kr.ac.skuniv.coopradar.me;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * [예시 프로필로 시작]이 저장하는 시연용 학생(application.yml {@code app.example-profile}). 실제 사람이 아니다.
 * 학과는 이름으로 department 시드에서 찾는다. 사는 곳은 area 시드에 없으면 비운다(좌표 대기 중, ADR-0007).
 */
@ConfigurationProperties("app.example-profile")
public record ExampleProfileProperties(
        String departmentName,
        int grade,
        int completedSemesters,
        BigDecimal gpa,
        boolean graduationExpected,
        String interestText,
        String homeAreaCode) {
}
