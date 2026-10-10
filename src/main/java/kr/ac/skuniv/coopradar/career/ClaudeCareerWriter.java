package kr.ac.skuniv.coopradar.career;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StructuredMessage;
import com.anthropic.models.messages.StructuredTextBlock;
import com.anthropic.models.messages.ThinkingConfigBetweenTools;
import java.util.Optional;
import kr.ac.skuniv.coopradar.career.CareerDrafts.UnitsDraft;
import kr.ac.skuniv.coopradar.explore.ExploreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Claude(직무 탐색과 같은 모델·키·제한 시간, {@code app.explore})로 실습 내용에서 다룬 능력단위를 고른다(ADR-0032).
 * 구조화 출력, 생각 단계 없음. 키가 없으면 부르지 않는다. 실습 내용·응답은 로그에 남기지 않는다.
 */
@Component
public class ClaudeCareerWriter implements CareerWriter {

    private static final Logger log = LoggerFactory.getLogger(ClaudeCareerWriter.class);
    private static final long MAX_TOKENS = 2000;

    private final ExploreProperties props;
    private volatile AnthropicClient client;

    public ClaudeCareerWriter(ExploreProperties props) {
        this.props = props;
    }

    @Override
    public boolean available() {
        return props.apiKey() != null && !props.apiKey().isBlank();
    }

    @Override
    public String model() {
        return props.model();
    }

    @Override
    public Optional<UnitsDraft> units(String system, String user) {
        if (!available()) {
            return Optional.empty();
        }
        try {
            var params = MessageCreateParams.builder()
                    .model(props.model())
                    .maxTokens(MAX_TOKENS)
                    .system(system)
                    .thinking(ThinkingConfigBetweenTools.builder().build())
                    .addUserMessage(user)
                    .outputConfig(UnitsDraft.class)
                    .build();
            StructuredMessage<UnitsDraft> message = client().messages().create(params);
            return message.content().stream()
                    .flatMap(b -> b.text().stream())
                    .map(StructuredTextBlock::text)
                    .findFirst();
        } catch (RuntimeException e) {
            log.warn("커리어 리포트 AI 호출 실패 — 능력단위 목록만 보여 줍니다: {}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private AnthropicClient client() {
        AnthropicClient c = client;
        if (c == null) {
            synchronized (this) {
                if (client == null) {
                    client = AnthropicOkHttpClient.builder()
                            .apiKey(props.apiKey())
                            .timeout(props.timeout())
                            .maxRetries(0)
                            .build();
                }
                c = client;
            }
        }
        return c;
    }
}
