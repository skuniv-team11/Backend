package kr.ac.skuniv.coopradar.spike;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import com.anthropic.core.ObjectMappers;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import org.junit.jupiter.api.Test;

/** API를 부르지 않고, SDK가 레코드에서 만든 스키마와 응답 역직렬화를 확인한다. */
class OperationPlanRequestsTest {

    private static String schemaOf(StructuredMessageCreateParams<?> params, String file) throws Exception {
        Object schema = params.rawParams().outputConfig().orElseThrow().format().orElseThrow().schema();
        String json = ObjectMappers.jsonMapper().writerWithDefaultPrettyPrinter().writeValueAsString(schema);
        Files.createDirectories(Path.of("build"));
        Files.writeString(Path.of("build", file), json);
        return json;
    }

    private static String institutionSchema() throws Exception {
        return schemaOf(
                OperationPlanRequests.institutionPart("system", "test.pdf", "JVBERi0xLjQK", "claude-sonnet-5-5", 16000),
                "derived-schema-institution.json");
    }

    private static String jobsSchema() throws Exception {
        return schemaOf(
                OperationPlanRequests.jobsPart("system", "test.pdf", "JVBERi0xLjQK", "claude-sonnet-5-5", 16000),
                "derived-schema-jobs.json");
    }

    @Test
    void 기관_파트_스키마가_만들어진다() throws Exception {
        String json = institutionSchema();

        assertThat(json).contains("중소기업", "협회기타", "코스닥", "판독불가");
        assertThat(json).contains("\"additionalProperties\" : false");
        assertThat(json).doesNotContain("anyOf");
        assertThat(json).doesNotContain("jobs");
    }

    @Test
    void 직무_파트_스키마가_만들어진다() throws Exception {
        String json = jobsSchema();

        assertThat(json).contains("채용연계형", "주기적상시적실시", "언급없음", "방학학기연계과정", "월기준");
        assertThat(json).contains("\"additionalProperties\" : false");
        assertThat(json).doesNotContain("anyOf");
        assertThat(json).doesNotContain("institution");
    }

    /**
     * 문법 크기 한도(ADR-0003)를 넘지 않는 것은 SDK가 반복되는 레코드를 $defs 로 묶어 주기 때문이다.
     * 묶기를 멈추면 SourcedText 가 스무 번 넘게 펼쳐져 400 "The compiled grammar is too large" 가 난다.
     */
    @Test
    void 반복되는_레코드는_defs로_묶인다() throws Exception {
        for (String json : new String[] { institutionSchema(), jobsSchema() }) {
            assertThat(json).contains("$defs", "SourcedText");
            assertThat(countOf(json, "$ref")).as("$ref 로 묶인 자리가 너무 적습니다").isGreaterThanOrEqualTo(8);
        }
    }

    @Test
    void 기관_파트_응답이_레코드로_읽힌다() throws Exception {
        String sample = """
            {"institution":{
              "name":{"value":"㈜선도소프트","page":1,"quote":"㈜선도소프트"},
              "businessNo":{"value":"119-86-82412","page":1,"quote":"119-86-82412"},
              "openedOn":{"value":"2014. 01. 17","page":1,"quote":"2014. 01. 17"},
              "ksicCode":{"value":"58222","page":1,"quote":"58222"},
              "employees":{"value":"80명","page":1,"quote":"80명"},
              "revenue":{"value":"15,000백만원","page":1,"quote":"15,000"},
              "address":{"value":"서울특별시 금천구","page":1,"quote":"서울특별시 금천구"},
              "homepage":{"value":"www.sundosoft.co.kr","page":1,"quote":"www.sundosoft.co.kr"},
              "size":{"value":"중소기업","page":1,"quote":"중소기업 [ O ]"},
              "listing":{"value":"비상장","page":1,"quote":"비상장 [ O ]"},
              "businessType":{"value":"서비스","page":1,"quote":"서비스"},
              "businessItem":{"value":"소프트웨어 개발","page":1,"quote":"소프트웨어 개발"},
              "dailyHours":{"value":"8시간","page":1,"quote":"8시간"},
              "weeklyHours":{"value":"40시간","page":1,"quote":"40시간"},
              "weeklyDays":{"value":"주 5일","page":1,"quote":"주 5일"},
              "workDaysText":{"value":"월~금","page":1,"quote":"월~금"},
              "selectionMethod":{"value":"면접선발","page":1,"quote":"면접선발"},
              "applicationDeadline":{"value":"일정별도협의","page":1,"quote":"일정별도협의"},
              "interviewDate":{"value":"","page":0,"quote":""},
              "finalSelectionDate":{"value":"","page":0,"quote":""}},
             "inconsistencies":[{"description":"교육목표의 전공과 학생요건의 전공이 다름",
              "pageA":2,"quoteA":"컴퓨터공학 전공 학생들이","pageB":2,"quoteB":"소프트웨어학과 (1명)"}]}
            """;
        OperationPlanInstitutionPart part =
                ObjectMappers.jsonMapper().readValue(sample, OperationPlanInstitutionPart.class);

        assertThat(part.institution().size().value()).isEqualTo(OperationPlanInstitutionPart.SizeValue.중소기업);
        assertThat(part.institution().listing().value()).isEqualTo(OperationPlanInstitutionPart.ListingValue.비상장);
        assertThat(part.institution().homepage().value()).isEqualTo("www.sundosoft.co.kr");
        assertThat(part.inconsistencies()).hasSize(1);
    }

    @Test
    void 직무_파트_응답이_레코드로_읽힌다() throws Exception {
        String sample = """
            {"jobs":[{
              "department":{"value":"지능정보사업부","page":2,"quote":"지능정보사업부"},
              "jobTitle":{"value":"AI·데이터 기반 소프트웨어 개발 인턴","page":2,"quote":"AI·데이터 기반"},
              "workAddress":{"value":"서울특별시 금천구","page":2,"quote":"서울특별시 금천구"},
              "course":{"value":"학기과정","page":2,"quote":"학기과정 [ V ]"},
              "jobType":{"value":"채용연계형","page":2,"quote":"채용연계형 [ V ]"},
              "period":{"value":"2026.09.01~2026.12.19","page":2,"quote":"2026.09.01"},
              "hours":{"value":"1일 8시간","page":2,"quote":"1일 8시간"},
              "weekdays":{"value":["월","화","수","목","금"],"page":2,"quote":"월"},
              "overtime":{"value":"없음","page":2,"quote":"연장실습 없음 [ V ]"},
              "laborContract":{"value":"N","page":2,"quote":"N [ V ]"},
              "stipendBasis":{"value":"월기준","page":2,"quote":"월 기준 [ V ]"},
              "stipendAmount":{"value":"2,156,880","page":2,"quote":"2,156,880"},
              "overtimePay":{"value":"","page":0,"quote":""},
              "payDay":{"value":"익월 10일","page":2,"quote":"익월 10일"},
              "benefits":{"value":["식사"],"page":2,"quote":"식사 [ V ]"},
              "educationGoal":{"value":"컴퓨터공학 전공 학생들이","page":2,"quote":"컴퓨터공학 전공 학생들이"},
              "jobOverview":{"value":"공간정보 소프트웨어 개발","page":2,"quote":"공간정보 소프트웨어 개발"},
              "weeklyPlan":[{"weeks":"1~2주차","content":"직무 교육 및 개발 환경 구축"},
                            {"weeks":"3~8주차","content":"프로젝트 참여"}],
              "majorRequirement":{"value":"소프트웨어학과 (1명)","page":2,"quote":"소프트웨어학과 (1명)"},
              "headcount":{"value":"1명","page":2,"quote":"(1명)"},
              "gradeRequirement":{"value":"3, 4학년","page":2,"quote":"3, 4학년"},
              "gpaRequirement":{"value":"3.5 이상","page":2,"quote":"3.5 이상"},
              "competencies":{"value":"Java, Python","page":2,"quote":"Java, Python"},
              "notes":{"value":"","page":0,"quote":""},
              "portfolio":{"value":"언급없음","page":0,"quote":""},
              "certificate":{"value":"우대","page":2,"quote":"정보처리기사 우대"}}]}
            """;
        OperationPlanJobsPart part = ObjectMappers.jsonMapper().readValue(sample, OperationPlanJobsPart.class);

        assertThat(part.jobs()).hasSize(1);
        OperationPlanJobsPart.Job job = part.jobs().get(0);
        assertThat(job.jobType().value()).isEqualTo(OperationPlanJobsPart.JobTypeValue.채용연계형);
        assertThat(job.course().value()).isEqualTo(OperationPlanJobsPart.CourseValue.학기과정);
        assertThat(job.stipendBasis().value()).isEqualTo(OperationPlanJobsPart.StipendBasisValue.월기준);
        assertThat(job.certificate().value()).isEqualTo(OperationPlanJobsPart.RequirementValue.우대);
        assertThat(job.weekdays().value()).hasSize(5);
        assertThat(job.weeklyPlan()).hasSize(2);
    }

    private static int countOf(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }
}
