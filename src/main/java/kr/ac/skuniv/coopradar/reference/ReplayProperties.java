package kr.ac.skuniv.coopradar.reference;

import java.time.LocalDate;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 모집기간 리플레이 설정(application.yml {@code app.replay}).
 *
 * @param defaultAsOf 시연 기본 기준일("시연 모드 · 2026-07-18 기준" 배너, asOf를 생략했을 때의 날짜).
 *                    환경변수 REPLAY_DEFAULT_AS_OF. 현재 회차 모집기간 밖이면 가까운 끝 날짜로 맞춘다
 */
@ConfigurationProperties("app.replay")
public record ReplayProperties(LocalDate defaultAsOf) {
}
