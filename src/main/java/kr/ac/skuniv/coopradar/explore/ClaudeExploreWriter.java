package kr.ac.skuniv.coopradar.explore;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StructuredMessage;
import com.anthropic.models.messages.StructuredTextBlock;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ThinkingConfigBetweenTools;
import java.util.List;
import java.util.Optional;
import kr.ac.skuniv.coopradar.explore.ExploreDrafts.RankDraft;
import kr.ac.skuniv.coopradar.explore.ExploreDrafts.WhyDraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Claude(Sonnet)로 탐색 순서와 '왜 맞나요'를 만든다(ADR-0031). 구조화 출력으로 받고, 생각 단계는 쓰지 않는다
 * ({@code between_tools} — 도구가 없어 생각하지 않음, E7과 같은 설정). 시스템 글(후보 직무 원문 + 규칙)은 프롬프트 캐시에 둔다.
 * 키가 없으면 부르지 않는다. 재시도 없이 제한 시간 안에 끝내고, 실패하면 빈 값. 학생 글·응답은 로그에 남기지 않는다.
 */
@Component
public class ClaudeExploreWriter implements ExploreWriter {

    private static final Logger log = LoggerFactory.getLogger(ClaudeExploreWriter.class);
    private static final long RANK_MAX_TOKENS = 1500;
    private static final long WHY_MAX_TOKENS = 1200;

    private final ExploreProperties props;
    private volatile AnthropicClient client;

    @Autowired
    public ClaudeExploreWriter(ExploreProperties props) {
        this.props = props;
    }

    /** 다른 주소(가짜 서버)로 보내는 클라이언트를 끼울 때(테스트). */
    ClaudeExploreWriter(ExploreProperties props, AnthropicClient client) {
        this.props = props;
        this.client = client;
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
    public Optional<RankDraft> rank(String system, String user) {
        return call(system, user, RANK_MAX_TOKENS, RankDraft.class, "탐색 순서");
    }

    @Override
    public Optional<WhyDraft> why(String system, String user) {
        return call(system, user, WHY_MAX_TOKENS, WhyDraft.class, "왜 맞나요");
    }

    private <T> Optional<T> call(String system, String user, long maxTokens, Class<T> type, String what) {
        if (!available()) {
            return Optional.empty();
        }
        try {
            var params = MessageCreateParams.builder()
                    .model(props.model())
                    .maxTokens(maxTokens)
                    .systemOfTextBlockParams(List.of(TextBlockParam.builder()
                            .text(system)
                            .cacheControl(CacheControlEphemeral.builder().build())
                            .build()))
                    .thinking(ThinkingConfigBetweenTools.builder().build())
                    .addUserMessage(user)
                    .outputConfig(type)
                    .build();
            StructuredMessage<T> message = client().messages().create(params);
            return message.content().stream()
                    .flatMap(b -> b.text().stream())
                    .map(StructuredTextBlock::text)
                    .findFirst();
        } catch (RuntimeException e) {
            log.warn("직무 탐색 AI 호출 실패({}) — AI 없이 이어 갑니다: {}", what, e.getClass().getSimpleName());
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
