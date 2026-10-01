package kr.ac.skuniv.coopradar.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 테스트에서 계약 스펙(static/openapi/contract.json — docs/api로 만든 것)을 읽는다.
 * 실제 응답이 계약 예시와 같은 모양(필드 이름·중첩·타입)인지 본다. 값은 비교하지 않는다.
 */
public final class Contract {

    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final JsonNode SPEC = load();

    private Contract() {
    }

    public static JsonNode spec() {
        return SPEC;
    }

    /** operationId의 응답 예시. 예시가 하나면 name은 null이어도 된다. */
    public static JsonNode responseExample(String operationId, int status, String name) {
        JsonNode examples = operation(operationId).get("responses").get(Integer.toString(status))
                .get("content").get("application/json").get("examples");
        JsonNode ex = name != null ? examples.get(name) : examples.properties().iterator().next().getValue();
        return ex.get("value");
    }

    public static JsonNode operation(String operationId) {
        for (Map.Entry<String, JsonNode> path : SPEC.get("paths").properties()) {
            for (Map.Entry<String, JsonNode> op : path.getValue().properties()) {
                if (op.getValue().path("operationId").toString().equals("\"" + operationId + "\"")) {
                    return op.getValue();
                }
            }
        }
        throw new AssertionError("계약에 operationId " + operationId + " 없음");
    }

    /** 실제 응답 JSON이 계약 예시와 같은 모양인지. null은 어느 쪽이든 허용(계약이 null 허용을 정한다). */
    public static void assertSameShape(String actualJson, JsonNode example) {
        List<String> problems = new ArrayList<>();
        compare(JSON.readTree(actualJson), example, "$", problems);
        assertThat(problems).as("계약 예시와 모양이 다름").isEmpty();
    }

    private static void compare(JsonNode actual, JsonNode expected, String path, List<String> problems) {
        if (actual.isNull() || expected.isNull()) {
            return;
        }
        if (actual.getNodeType() != expected.getNodeType()) {
            problems.add(path + ": " + actual.getNodeType() + " ≠ 계약 " + expected.getNodeType());
            return;
        }
        if (actual.isObject()) {
            var a = new TreeSet<String>();
            actual.properties().forEach(e -> a.add(e.getKey()));
            var e = new TreeSet<String>();
            expected.properties().forEach(x -> e.add(x.getKey()));
            if (!a.equals(e)) {
                var onlyActual = new TreeSet<>(a);
                onlyActual.removeAll(e);
                var onlyContract = new TreeSet<>(e);
                onlyContract.removeAll(a);
                problems.add(path + ": 응답에만 " + onlyActual + ", 계약에만 " + onlyContract);
            }
            for (String k : a) {
                if (e.contains(k)) {
                    compare(actual.get(k), expected.get(k), path + "." + k, problems);
                }
            }
        } else if (actual.isArray() && !actual.isEmpty() && !expected.isEmpty()) {
            compare(actual.get(0), expected.get(0), path + "[0]", problems);
        }
    }

    private static JsonNode load() {
        try (InputStream in = new ClassPathResource("static/openapi/contract.json").getInputStream()) {
            return JSON.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
