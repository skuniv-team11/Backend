package kr.ac.skuniv.coopradar.spike;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * 운영계획서 추출 시스템 프롬프트는 두 곳에 있다 — 실험용(파이썬)과 서비스용(Java 리소스).
 *
 * 한쪽만 고치면 E1과 E3가 서로 다른 규칙으로 돌아간다. 실제로 2026-09-30에 직무 단위를
 * '블록마다'에서 '팀마다'로 바꿀 때 파이썬 쪽만 고쳐져 갈라졌다. scripts/check_pipeline.py도
 * 같은 검사를 하지만, CI는 바뀐 영역만 돌리므로 src/ 만 고친 경우를 여기서 잡는다.
 */
class PromptSyncTest {

    private static final Path PIPELINE = Path.of("pipeline/e1_operation_plan/prompt_system.md");
    private static final Path SERVICE = Path.of("src/main/resources/prompts/operation-plan-system.md");

    @Test
    void 운영계획서_시스템_프롬프트는_파이프라인과_서비스가_같아야_한다() throws Exception {
        assertThat(PIPELINE).as("실험용 프롬프트가 없습니다").exists();
        assertThat(SERVICE).as("서비스용 프롬프트가 없습니다").exists();

        assertThat(Files.readString(SERVICE))
                .as("두 프롬프트가 다릅니다. 고친 쪽을 다른 쪽에 복사하세요: cp %s %s", PIPELINE, SERVICE)
                .isEqualTo(Files.readString(PIPELINE));
    }
}
