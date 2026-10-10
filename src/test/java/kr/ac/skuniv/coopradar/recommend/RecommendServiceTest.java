package kr.ac.skuniv.coopradar.recommend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import kr.ac.skuniv.coopradar.TestcontainersConfiguration;
import kr.ac.skuniv.coopradar.eligibility.ProfileInput;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.web.servlet.FlashMap;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;
import tools.jackson.databind.json.JsonMapper;

/**
 * 규칙 적합도 추천(직무 탐색이 AI 대신 규칙 추천으로 갈 때 쓴다 — #15 API는 ADR-0036에서 지움). 2026-2 실제 시드로 본다 —
 * 예시 학생(메이크업디자인학과 43)의 시연 흐름이 데이터로 이어지는지. 서비스를 바로 부르고 결과를 JSON으로 바꿔 본다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RecommendServiceTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    RecommendService service;

    @Test
    void 예시_학생은_소서_국내_해외_마케팅이_1_2위() throws Exception {
        String body = recommend(profile(3, 5, "\"뷰티 브랜드 SNS 마케팅\""))
                .andExpect(jsonPath("$.round.termCode").value("2026-2"))
                .andExpect(jsonPath("$.items.length()").value(5))
                .andExpect(jsonPath("$.items[0].jobId").value(122))
                .andExpect(jsonPath("$.items[0].fit").value("HIGH"))
                // 기본 문장: 내 값으로 갖춘 조건 → 내 학과와 선호 전공 → 관심과 겹친 원문 → 같은 팀 해외 마케팅과의 차이
                // (ADR-0020·0024)
                .andExpect(jsonPath("$.items[0].reasonTemplate").value(
                        "3학년·학점 3.4라 지원 조건(3·4학년, 학점 3.0 이상)을 모두 갖췄어요. "
                                + "메이크업디자인학과는 회사가 선호하는 전공 범위('미용예술대학')에 들어가요. "
                                + "관심 분야가 직무 개요 '자사 SNS 채널 콘텐츠 운영 및 홍보 마케팅 업무 지원'과 겹쳐요. "
                                + "같은 팀의 해외 마케팅과 달리 '영어 가능자' 요건은 없어요."))
                // 인용은 관심과 겹치는 같은 팀 수기 한 줄 → 문장이 짚은 계획서 조각(글머리표 없이)
                .andExpect(jsonPath("$.items[0].citations[0].sourceType").value("TESTIMONIAL"))
                .andExpect(jsonPath("$.items[0].citations[0].quote").value("SNS 계정 관리 및 업로드"))
                .andExpect(jsonPath("$.items[0].citations[0].page").value(15))
                .andExpect(jsonPath("$.items[0].citations[1].sourceType").value("OPERATION_PLAN"))
                .andExpect(jsonPath("$.items[0].citations[1].quote").value("자사 SNS 채널 콘텐츠 운영 및 홍보 마케팅 업무 지원"))
                .andExpect(jsonPath("$.items[1].jobId").value(123))
                .andExpect(jsonPath("$.items[1].reasonTemplate").value(org.hamcrest.Matchers.endsWith(
                        "같은 팀의 국내 마케팅과 달리 '영어 가능자' 요건이 있어요.")))
                // AMD(세일즈팀)에는 마케팅팀 수기를 붙이지 않는다
                .andExpect(jsonPath("$.items[2].jobId").value(120))
                .andExpect(jsonPath("$.items[2].citations.length()").value(1))
                .andExpect(jsonPath("$.items[2].citations[0].sourceType").value("OPERATION_PLAN"))
                // 선호 전공 밖이어도 관심 분야가 가까운 세정 SNS·영상은 '보통'으로 뒤에 온다. 내 학과 이름으로 그 사실을 말한다.
                // 전공 무관인 강의제작(116)은 관심 문장을 적었는데 겹치지 않으면 추천 이유가 없다(ADR-0026)
                .andExpect(jsonPath("$.items[3].jobId").value(102))
                .andExpect(jsonPath("$.items[3].fit").value("MEDIUM"))
                .andExpect(jsonPath("$.items[3].reasonTemplate").value(
                        "3학년이라 지원 조건(3·4학년)을 모두 갖췄어요. "
                                + "메이크업디자인학과는 회사가 선호하는 전공(무대패션디자인전공·광고홍보콘텐츠학과 등)과는 거리가 있는 전공이에요. "
                                + "지난 매칭(2025-2~2026-2)에서 이렇게 먼 전공으로 매칭된 학생은 73명 중 7명이었어요. "
                                + "관심 분야와 직무 내용이 가까워요."))
                .andExpect(jsonPath("$.items[4].jobId").value(103))
                .andExpect(jsonPath("$.blockedBy").isEmpty())
                .body();
        List<Integer> ranks = JsonPath.read(body, "$.items[*].rank");
        assertThat(ranks).containsExactly(1, 2, 3, 4, 5);
        List<String> verdicts = JsonPath.read(body, "$.items[*].verdict");
        assertThat(verdicts).containsOnly("ELIGIBLE");
        // 선호 전공이 맞는 3직무(120·122·123)가 상위 3개. 4학년만 받는 비욘드(134)는 지원 불가라 빠진다(ADR-0024)
        List<Integer> top3 = JsonPath.read(body, "$.items[0:3].jobId");
        assertThat(top3).containsExactlyInAnyOrder(120, 122, 123);
        assertThat(JsonPath.<List<Integer>>read(body, "$.items[*].jobId")).doesNotContain(116);
        // 높음은 근거(선호 전공 또는 가까운 관심)가 있고 어긋난 근거가 없을 때만: 122·123만 높음(ADR-0026)
        assertThat(JsonPath.<List<String>>read(body, "$.items[*].fit")).containsExactly("HIGH", "HIGH", "MEDIUM", "MEDIUM", "MEDIUM");
        assertThat(JsonPath.<List<Integer>>read(body, "$.items[*].jobId")).doesNotContain(134);
        assertThat(body).doesNotContain("score");
        List<String> quotes = JsonPath.read(body, "$.items[*].citations[*].quote");
        assertThat(quotes).noneMatch(q -> q.startsWith("*"));
    }

    @Test
    void 기준일에_마감된_직무는_추천에서_빠진다() throws Exception {
        // 광고홍보콘텐츠학과(10) 4학년: 선호 전공이 맞는 101 AE는 7/18 센터 모집마감이라 기준일(7/23)에 빠진다(ADR-0016)
        String body = recommend("""
                {"profile": {"departmentId": 10, "grade": 4, "completedSemesters": 7, "gpa": 4.0,
                 "graduationExpected": false, "interestText": "광고 캠페인 기획", "homeAreaCode": null}}""")
                .andExpect(jsonPath("$.items.length()").value(5))
                .body();
        assertThat(JsonPath.<List<Integer>>read(body, "$.items[*].jobId")).doesNotContain(101);
    }

    @Test
    void 전부_지원_불가면_items는_비고_blockedBy에_막은_요건별_직무_수() throws Exception {
        // 2학년·3학기: 이수 학기로 40개 모두 막히고, 학년(3학년 이상) 40 · 학점 하한 3.5·3.6 5 · 필수 자격증 1도 함께 센다(ADR-0024)
        String body = recommend(profile(2, 3, "null"))
                .andExpect(jsonPath("$.items").isEmpty())
                .body();
        assertThat(JsonPath.<List<String>>read(body, "$.blockedBy[*].item")).containsExactly("이수 학기", "학년", "학점", "자격증");
        assertThat(JsonPath.<List<Integer>>read(body, "$.blockedBy[*].count")).containsExactly(40, 40, 5, 1);
    }

    @Test
    void 자격증은_답하지_않아도_없음으로_보고_blockedBy에_센다() throws Exception {
        // ADR-0021·0024: certificates가 null이든 []이든 미용 시술 보조는 자격증으로 막힌다
        for (String certificates : new String[] {"null", "[]"}) {
            String body = recommend("""
                    {"profile": {"departmentId": 43, "grade": 2, "completedSemesters": 3, "gpa": 3.4,
                     "graduationExpected": false, "interestText": null, "homeAreaCode": null, "certificates": %s}}"""
                    .formatted(certificates))
                        .body();
            assertThat(JsonPath.<List<String>>read(body, "$.blockedBy[*].item")).contains("자격증");
        }
    }

    @Test
    void 선호_전공도_관심도_맞지_않는_직무는_추천하지_않아_5개보다_적을_수_있다() throws Exception {
        // ADR-0022·0026·0028: 컴퓨터공학과(26)는 선호 전공 범위('이공계열')에 드는 직무가 미디어 코퍼스(136)뿐이고,
        // '백엔드 개발자'는 선도소프트 소프트웨어 개발(119, 학점 3.5 이상, 선호 전공 소프트웨어학과)과 가장 많이 겹친다.
        // 컴퓨터공학과는 소프트웨어학과와 같은 묶음이라 119는 가까운 전공 + 관심 많이 겹침 → 높음.
        // 전공 무관 자리(116 강의제작)·미용 시술 보조 등은 이유가 없어 채우지 않는다
        String body = recommend("""
                {"profile": {"departmentId": 26, "grade": 3, "completedSemesters": 5, "gpa": 4.0,
                 "graduationExpected": false, "interestText": "백엔드 개발자", "homeAreaCode": null}}""")
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.blockedBy").isEmpty())
                // 119는 가까운 전공 + 관심 많이 겹침이라 높음, 136은 관심과 겹치는 내용이 적어 보통 — 문장이 그 이유를 말한다
                .andExpect(jsonPath("$.items[0].jobId").value(119))
                .andExpect(jsonPath("$.items[0].fit").value("HIGH"))
                .andExpect(jsonPath("$.items[0].reasonTemplate").value(org.hamcrest.Matchers.containsString(
                        "컴퓨터공학과는 회사가 선호하는 전공('소프트웨어학과')과 가까운 전공이에요. "
                                + "지난 매칭(2025-2~2026-2)에서 선호 전공 밖 학생 18명 중 11명이 이런 가까운 전공이었어요.")))
                .andExpect(jsonPath("$.items[1].jobId").value(136))
                .andExpect(jsonPath("$.items[1].fit").value("MEDIUM"))
                .andExpect(jsonPath("$.items[1].reasonTemplate").value(org.hamcrest.Matchers.endsWith(
                        "컴퓨터공학과는 회사가 선호하는 전공 범위('이공계열')에 들어가요. 관심 분야와 겹치는 내용은 적어요.")))
                .body();
        assertThat(JsonPath.<List<Integer>>read(body, "$.items[*].jobId")).doesNotContain(116);

        // 학점이 하한(3.5)보다 낮으면 119는 지원 불가라 빠지고, 가장 많이 겹친 직무가 빠졌다고 덜 겹친 마케팅 직무가
        // 관심 분야 이유로 들어오지도 않는다(관심 유사도는 회차 직무 전부로 잰다, ADR-0024)
        String lowGpa = recommend("""
                {"profile": {"departmentId": 26, "grade": 3, "completedSemesters": 5, "gpa": 3.4,
                 "graduationExpected": false, "interestText": "백엔드 개발자", "homeAreaCode": null}}""")
                .body();
        assertThat(JsonPath.<List<Integer>>read(lowGpa, "$.items[*].jobId")).containsExactly(136);

        // 선호 전공이 맞는 직무가 없는 학과(무용예술학부 53)가 관심 분야를 비우면 전공 무관 자리(116 강의제작)만 '보통'으로.
        // 관심 문장이 없을 때만 전공 무관이 이유가 된다(ADR-0025·0026) — 위처럼 관심을 적었는데 겹치지 않으면 넣지 않는다
        String none = recommend("""
                {"profile": {"departmentId": 53, "grade": 3, "completedSemesters": 5, "gpa": 4.5,
                 "graduationExpected": false, "interestText": null, "homeAreaCode": null, "certificates": []}}""")
                .andExpect(jsonPath("$.items[0].fit").value("MEDIUM"))
                .andExpect(jsonPath("$.items[0].reasonTemplate").value("3학년이라 지원 조건(3·4학년)을 모두 갖췄어요. 전공을 따지지 않는 자리예요."))
                .andExpect(jsonPath("$.blockedBy").isEmpty())
                .body();
        assertThat(JsonPath.<List<Integer>>read(none, "$.items[*].jobId")).containsExactly(116);
    }

    /** 결과 JSON. MockMvc의 jsonPath 검사를 그대로 쓴다(응답 대신 이 글로 본다). */
    record Result(String body) {

        Result andExpect(ResultMatcher matcher) throws Exception {
            MockHttpServletResponse response = new MockHttpServletResponse();
            response.setCharacterEncoding("UTF-8");
            response.setContentType("application/json");
            response.getWriter().write(body);
            matcher.match(new MvcResult() {
                public MockHttpServletRequest getRequest() {
                    return new MockHttpServletRequest();
                }

                public MockHttpServletResponse getResponse() {
                    return response;
                }

                public Object getHandler() {
                    return null;
                }

                public HandlerInterceptor[] getInterceptors() {
                    return null;
                }

                public ModelAndView getModelAndView() {
                    return null;
                }

                public Exception getResolvedException() {
                    return null;
                }

                public FlashMap getFlashMap() {
                    return null;
                }

                public Object getAsyncResult() {
                    return null;
                }

                public Object getAsyncResult(long timeToWait) {
                    return null;
                }
            });
            return this;
        }
    }

    /** 본문 {"profile": {...}} 글 → 서비스 결과 JSON. */
    private Result recommend(String body) {
        ProfileInput profile = JSON.treeToValue(JSON.readTree(body).get("profile"), ProfileInput.class);
        return new Result(JSON.writeValueAsString(service.recommend(profile)));
    }

    private static String profile(int grade, int semesters, String interest) {
        return """
                {"profile": {"departmentId": 43, "grade": %d, "completedSemesters": %d, "gpa": 3.4,
                 "graduationExpected": false, "interestText": %s, "homeAreaCode": null}}""".formatted(grade, semesters, interest);
    }
}
