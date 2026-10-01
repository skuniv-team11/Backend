package kr.ac.skuniv.coopradar.center;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 센터 현황판 설정(application.yml {@code app.center}).
 *
 * @param narrowPoolBelow 선호 전공 재학생 수가 이 값보다 적으면 위험 요인 NARROW_POOL. 2026-2 시드 분포를 보고 정했다
 *                        (ADR-0016). 환경변수 CENTER_NARROW_POOL_BELOW
 */
@ConfigurationProperties("app.center")
public record CenterProperties(int narrowPoolBelow) {
}
