package kr.ac.skuniv.coopradar.spike;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import com.anthropic.core.ObjectMappers;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import org.junit.jupiter.api.Test;

/** API를 부르지 않고, SDK가 레코드에서 만든 스키마와 응답 역직렬화를 확인한다. */
class OperationPlanRequestsTest {

    @Test
    void 레코드에서_지원되는_스키마가_만들어진다() throws Exception {
        StructuredMessageCreateParams<OperationPlanLite> params =
                OperationPlanRequests.build("system", "test.pdf", "JVBERi0xLjQK", "claude-sonnet-5-5", 16000);

        Object schema = params.rawParams().outputConfig().orElseThrow().format().orElseThrow().schema();
        String json = ObjectMappers.jsonMapper().writerWithDefaultPrettyPrinter().writeValueAsString(schema);
        Files.createDirectories(Path.of("build"));
        Files.writeString(Path.of("build", "derived-schema.json"), json);

        assertThat(json).contains("\"채용연계형\"", "\"주기적상시적실시\"", "\"언급없음\"", "\"중소기업\"");
        assertThat(json).contains("\"additionalProperties\" : false");
        assertThat(json).doesNotContain("anyOf");
    }

    @Test
    void 모델_응답_JSON이_레코드로_읽힌다() throws Exception {
        String sample = """
            {"institution":{"name":{"value":"㈜선도소프트","page":1,"quote":"㈜선도소프트"},
              "businessNo":{"value":"119-86-82412","page":1,"quote":"119-86-82412"},
              "openedOn":{"value":"2014. 01. 17","page":1,"quote":"2014. 01. 17"},
              "ksicCode":{"value":"58222","page":1,"quote":"58222"},
              "employees":{"value":"80명","page":1,"quote":"80명"},
              "size":{"value":"중소기업","page":1,"quote":"중소기업 [ O ]"},
              "listing":{"value":"비상장","page":1,"quote":"비상장 [ O ]"},
              "selectionMethod":{"value":"면접선발","page":1,"quote":"면접선발"}},
             "jobs":[{"department":{"value":"지능정보사업부","page":2,"quote":"지능정보사업부"},
              "jobTitle":{"value":"AI·데이터 기반 소프트웨어 개발 및 시스템 구축 인턴","page":2,"quote":"AI·데이터 기반"},
              "jobType":{"value":"채용연계형","page":2,"quote":"채용연계형 [ ⋁ ]"},
              "period":{"value":"","page":0,"quote":""},"hours":{"value":"","page":0,"quote":""},
              "weekdays":{"value":["월","화","수","목","금"],"page":2,"quote":"월"},
              "overtime":{"value":"없음","page":2,"quote":"연장실습 없음 [ ⋁ ]"},
              "laborContract":{"value":"N","page":2,"quote":"N [ ⋁ ]"},
              "stipendAmount":{"value":"2,156,880","page":2,"quote":"2,156,880"},
              "benefits":{"value":["식사"],"page":2,"quote":"식사 [ ⋁ ]"},
              "majorRequirement":{"value":"소프트웨어학과 (1명)","page":2,"quote":"소프트웨어학과 (1명)"},
              "headcount":{"value":"1명","page":2,"quote":"(1명)"},
              "gradeRequirement":{"value":"3, 4학년","page":2,"quote":"3, 4학년"},
              "gpaRequirement":{"value":"3.5 이상","page":2,"quote":"3.5 이상"},
              "portfolio":{"value":"언급없음","page":0,"quote":""}}],
             "inconsistencies":[{"description":"교육목표는 컴퓨터공학 전공 학생, 전공 요건은 소프트웨어학과",
              "pageA":2,"quoteA":"컴퓨터공학 전공 학생들이","pageB":2,"quoteB":"소프트웨어학과 (1명)"}]}
            """;
        OperationPlanLite plan = ObjectMappers.jsonMapper().readValue(sample, OperationPlanLite.class);

        assertThat(plan.jobs()).hasSize(1);
        assertThat(plan.jobs().get(0).jobType().value()).isEqualTo(OperationPlanLite.JobTypeValue.채용연계형);
        assertThat(plan.jobs().get(0).weekdays().value()).hasSize(5);
        assertThat(plan.institution().size().value()).isEqualTo(OperationPlanLite.SizeValue.중소기업);
        assertThat(plan.inconsistencies()).hasSize(1);
    }
}
