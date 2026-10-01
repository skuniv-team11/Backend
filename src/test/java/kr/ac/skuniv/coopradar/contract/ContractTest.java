package kr.ac.skuniv.coopradar.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.ac.skuniv.coopradar.TestcontainersConfiguration;
import kr.ac.skuniv.coopradar.auth.PublicApi;
import kr.ac.skuniv.coopradar.auth.RequireRole;
import kr.ac.skuniv.coopradar.common.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.JsonNode;

/**
 * 구현이 계약(docs/api → contract.json)에서 벗어나지 않게 막는다(ADR-0012).
 * - 구현한 /api/** 엔드포인트는 모두 계약에 있어야 하고, 권한 표시(@PublicApi·@RequireRole)가 계약의 권한과 같아야 한다.
 * - 서버 ErrorCode는 계약 오류 코드 표와 같은 집합·HTTP 상태여야 한다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ContractTest {

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;

    @Test
    void 구현한_엔드포인트의_권한이_계약과_같다() {
        Map<String, String> contract = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> path : Contract.spec().get("paths").properties()) {
            for (Map.Entry<String, JsonNode> op : path.getValue().properties()) {
                contract.put(op.getKey().toUpperCase() + " " + path.getKey(), text(op.getValue().get("x-auth")));
            }
        }
        List<String> problems = new ArrayList<>();
        int implemented = 0;
        for (var entry : mappings.getHandlerMethods().entrySet()) {
            HandlerMethod handler = entry.getValue();
            for (String pattern : entry.getKey().getPatternValues()) {
                if (!pattern.startsWith("/api/")) {
                    continue;
                }
                for (RequestMethod method : entry.getKey().getMethodsCondition().getMethods()) {
                    String key = method.name() + " " + pattern;
                    implemented++;
                    if (!contract.containsKey(key)) {
                        problems.add(key + ": 계약(docs/api)에 없는 엔드포인트");
                        continue;
                    }
                    String actual = authOf(handler);
                    if (!actual.equals(contract.get(key))) {
                        problems.add(key + ": 코드 권한 " + actual + " ≠ 계약 " + contract.get(key));
                    }
                }
            }
        }
        assertThat(implemented).isPositive();
        assertThat(problems).isEmpty();
    }

    @Test
    void 오류_코드가_계약과_같다() {
        JsonNode schema = Contract.spec().get("components").get("schemas").get("ErrorCode");
        Map<String, Integer> contract = new LinkedHashMap<>();
        schema.get("x-status").properties().forEach(e -> contract.put(e.getKey(), e.getValue().intValue()));
        Map<String, Integer> server = new LinkedHashMap<>();
        for (ErrorCode c : ErrorCode.values()) {
            server.put(c.name(), c.status().value());
        }
        assertThat(server).isEqualTo(contract);
    }

    private static String authOf(HandlerMethod h) {
        if (find(h, PublicApi.class) != null) {
            return "공개";
        }
        RequireRole role = find(h, RequireRole.class);
        return role == null ? "로그인" : role.value().name();
    }

    private static <A extends java.lang.annotation.Annotation> A find(HandlerMethod h, Class<A> type) {
        A onMethod = AnnotatedElementUtils.findMergedAnnotation(h.getMethod(), type);
        return onMethod != null ? onMethod : AnnotatedElementUtils.findMergedAnnotation(h.getBeanType(), type);
    }

    private static String text(JsonNode node) {
        String s = node.toString();
        return s.substring(1, s.length() - 1);
    }
}
