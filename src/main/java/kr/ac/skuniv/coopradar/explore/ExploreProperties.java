package kr.ac.skuniv.coopradar.explore;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 직무 탐색(#28~#32) 설정(application.yml {@code app.explore}, ADR-0031).
 *
 * @param apiKey         ANTHROPIC_API_KEY. 비면 AI를 부르지 않고 규칙 추천(NO_KEY)으로 답한다
 * @param model          탐색·설명 모델(claude-sonnet-5-5 — E7에서 haiku는 키워드보다 낮았다)
 * @param timeout        AI 호출 하나의 제한 시간. 넘으면 AI_ERROR
 * @param perUserPerHour 계정당 1시간 AI 사용 수(탐색 1번 = 1, '왜 맞나요' 1곳 = 1). 0이면 끔
 * @param perIpPerHour   IP당 1시간. 0이면 끔
 * @param perDay         서버 전체 하루(한국 시간). 0이면 끔
 */
@ConfigurationProperties("app.explore")
public record ExploreProperties(String apiKey, String model, Duration timeout, int perUserPerHour, int perIpPerHour,
                                int perDay) {
}
