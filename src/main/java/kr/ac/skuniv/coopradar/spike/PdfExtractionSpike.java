package kr.ac.skuniv.coopradar.spike;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.StructuredMessage;
import com.anthropic.models.messages.StructuredTextBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * E3 스파이크: 운영계획서 PDF → Claude(structured outputs) → OperationPlanLite.
 *
 * 실행(backend 폴더에서):
 *   ./gradlew bootJar
 *   ANTHROPIC_API_KEY=... java -jar build/libs/coop-radar-backend-0.0.1.jar --spring.profiles.active=spike "<PDF 경로>" ["<PDF 경로>" ...]
 * 결과: build/spike-out/<파일명>.json
 */
@Component
@Profile("spike")
public class PdfExtractionSpike implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PdfExtractionSpike.class);
    private static final long MAX_BASE64_CHARS = 31L * 1024 * 1024; // 요청 한도 32MB 안쪽

    private final String model;
    private final long maxTokens;

    public PdfExtractionSpike(@Value("${app.spike.model}") String model,
                              @Value("${app.spike.max-tokens}") long maxTokens) {
        this.model = model;
        this.maxTokens = maxTokens;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        List<String> pdfs = args.getNonOptionArgs();
        if (pdfs.isEmpty()) {
            log.warn("PDF 경로를 인자로 넘기세요. 예: --args=\"--spring.profiles.active=spike ./plan.pdf\"");
            return;
        }
        if (System.getenv("ANTHROPIC_API_KEY") == null || System.getenv("ANTHROPIC_API_KEY").isBlank()) {
            log.error("환경변수 ANTHROPIC_API_KEY가 없습니다. Claude Console에서 키를 만들어 넣으세요.");
            return;
        }
        String system = new String(
                new ClassPathResource("prompts/operation-plan-system.md").getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        AnthropicClient client = AnthropicOkHttpClient.builder()
                .fromEnv()                        // ANTHROPIC_API_KEY
                .timeout(Duration.ofMinutes(10))
                .build();
        JsonMapper json = JsonMapper.builder().build();
        Path outDir = Path.of("build", "spike-out");
        Files.createDirectories(outDir);

        for (String arg : pdfs) {
            Path pdf = Path.of(arg);
            String b64 = Base64.getEncoder().encodeToString(Files.readAllBytes(pdf));
            if (b64.length() > MAX_BASE64_CHARS) {
                log.error("{}: base64 {}MB — 32MB 요청 한도에 걸립니다. 압축하거나 쪽을 나누세요",
                        pdf.getFileName(), b64.length() / 1_000_000);
                continue;
            }
            String name = pdf.getFileName().toString();
            long t0 = System.nanoTime();
            StructuredMessage<OperationPlanLite> msg =
                    client.messages().create(OperationPlanRequests.build(system, name, b64, model, maxTokens));
            long ms = (System.nanoTime() - t0) / 1_000_000;

            OperationPlanLite plan = msg.content().stream()
                    .flatMap(b -> b.text().stream())
                    .map(StructuredTextBlock::text)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("응답에 텍스트 블록이 없습니다: " + msg.stopReason()));

            Path out = outDir.resolve(name.replaceAll("\\.pdf$", "") + ".json");
            write(out, json.writerWithDefaultPrettyPrinter().writeValueAsString(plan));
            log.info("{} → {} | {}ms, 입력 {} / 출력 {} 토큰, stop={}, 직무 {}개",
                    name, out, ms, msg.usage().inputTokens(), msg.usage().outputTokens(),
                    msg.stopReason().map(Object::toString).orElse("-"), plan.jobs().size());
        }
    }

    private static void write(Path path, String content) throws IOException {
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }
}
