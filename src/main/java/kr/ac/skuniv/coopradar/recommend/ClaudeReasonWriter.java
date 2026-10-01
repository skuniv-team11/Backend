package kr.ac.skuniv.coopradar.recommend;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StructuredMessage;
import com.anthropic.models.messages.StructuredTextBlock;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Claude(Haiku)로 이유 문장 초안을 만든다. 키가 없으면 부르지 않는다. 재시도 없이 제한 시간 안에 끝내고,
 * 실패하면 빈 값을 준다(화면은 기본 문장으로 유지). 프로필 값·문장은 로그에 남기지 않는다.
 */
@Component
public class ClaudeReasonWriter implements ReasonWriter {

    private static final Logger log = LoggerFactory.getLogger(ClaudeReasonWriter.class);
    private static final long MAX_TOKENS = 400;

    private final ReasonProperties props;
    private volatile AnthropicClient client;

    public ClaudeReasonWriter(ReasonProperties props) {
        this.props = props;
    }

    @Override
    public Optional<ReasonDraft> write(String systemPrompt, String userMessage) {
        if (props.apiKey() == null || props.apiKey().isBlank()) {
            return Optional.empty();
        }
        try {
            var params = MessageCreateParams.builder()
                    .model(props.model())
                    .maxTokens(MAX_TOKENS)
                    .system(systemPrompt)
                    .addUserMessage(userMessage)
                    .outputConfig(ReasonDraft.class)
                    .build();
            StructuredMessage<ReasonDraft> message = client().messages().create(params);
            return message.content().stream()
                    .flatMap(b -> b.text().stream())
                    .map(StructuredTextBlock::text)
                    .findFirst();
        } catch (RuntimeException e) {
            log.warn("이유 문장 LLM 호출 실패 — 기본 문장으로 대신합니다: {}", e.getClass().getSimpleName());
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
