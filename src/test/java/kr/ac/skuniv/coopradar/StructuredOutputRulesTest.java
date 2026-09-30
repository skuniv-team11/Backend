package kr.ac.skuniv.coopradar;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;

/**
 * 구조화 출력(Claude structured outputs)에 쓰는 enum 규칙 — ADR-0003.
 *
 * Anthropic Java SDK의 스키마 생성기는 enum 상수의 @JsonProperty를 무시하고 상수 이름을 그대로 모델에 보낸다.
 * @JsonProperty로 한글 값을 붙이면 모델은 영문 상수로 답하고 역직렬화는 한글 값을 기다려 실패한다.
 * 그래서 enum은 한글 상수명(예: 채용연계형)을 쓰고 @JsonProperty를 붙이지 않는다.
 */
class StructuredOutputRulesTest {

    @Test
    void 구조화_출력_enum에는_JsonProperty를_붙이지_않는다() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(AnnotatedBeanDefinition beanDefinition) {
                return true;
            }
        };
        scanner.addIncludeFilter((reader, factory) -> "java.lang.Enum".equals(reader.getClassMetadata().getSuperClassName()));

        List<String> violations = new ArrayList<>();
        int enums = 0;
        for (BeanDefinition bd : scanner.findCandidateComponents("kr.ac.skuniv.coopradar")) {
            Class<?> type = Class.forName(bd.getBeanClassName());
            enums++;
            for (Field f : type.getDeclaredFields()) {
                if (f.isEnumConstant() && f.isAnnotationPresent(JsonProperty.class)) {
                    violations.add(type.getName() + "." + f.getName());
                }
            }
        }

        assertThat(enums).as("검사할 enum을 하나도 찾지 못했습니다(패키지 경로 확인)").isPositive();
        assertThat(violations)
                .as("enum 상수에 @JsonProperty가 있습니다. 상수 이름을 문서 표기(공백·슬래시 제외 한글)로 바꾸고 "
                        + "@JsonProperty를 지우세요. 이유는 docs/decisions/0003-structured-output-schema.md")
                .isEmpty();
    }
}
