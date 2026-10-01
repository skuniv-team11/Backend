package kr.ac.skuniv.coopradar.recommend;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 추천 이유 문장(#16) 설정(application.yml {@code app.reason}).
 *
 * @param apiKey         ANTHROPIC_API_KEY. 비면 LLM을 부르지 않고 기본 문장(TEMPLATE)으로 답한다
 * @param model          설명용 모델(claude-haiku-4-5-20251001)
 * @param timeout        이 시간을 넘기면 포기하고 기본 문장(docs/api: 5초)
 * @param perUserPerHour 계정당 1시간 LLM 호출 수. 넘으면 기본 문장(0이면 끔)
 * @param perIpPerHour   IP당 1시간 LLM 호출 수. 넘으면 기본 문장(0이면 끔)
 * @param cacheSize      메모리 캐시 문장 수(서버가 다시 뜨면 비워진다)
 */
@ConfigurationProperties("app.reason")
public record ReasonProperties(String apiKey, String model, Duration timeout, int perUserPerHour, int perIpPerHour,
                               int cacheSize) {
}
