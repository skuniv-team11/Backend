package kr.ac.skuniv.coopradar.recommend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import kr.ac.skuniv.coopradar.TestcontainersConfiguration;
import kr.ac.skuniv.coopradar.contract.Contract;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** docs/api #15 적합도 추천. 2026-2 실제 시드로 본다 — 예시 학생(메이크업디자인학과 43)의 시연 흐름이 데이터로 이어지는지. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class RecommendApiTest {

    private static final AtomicInteger IP = new AtomicInteger(1);

    @Autowired
    MockMvc mvc;

    @Test
    void 예시_학생은_소서_국내_해외_마케팅이_1_2위이고_계약_모양과_같다() throws Exception {
        String body = recommend(guestToken("STUDENT"), profile(3, 5, "\"뷰티 브랜드 SNS 마케팅\""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.round.termCode").value("2026-2"))
                .andExpect(jsonPath("$.items.length()").value(5))
                .andExpect(jsonPath("$.items[0].jobId").value(122))
                .andExpect(jsonPath("$.items[0].fit").value("HIGH"))
                .andExpect(jsonPath("$.items[0].reasonStatus").value("PENDING"))
                // 기본 문장: 내 값으로 갖춘 조건 → 내 학과와 선호 전공 → 관심과 겹친 원문 → 같은 팀 해외 마케팅과의 차이
                // (ADR-0020·0024)
                .andExpect(jsonPath("$.items[0].reasonTemplate").value(
                        "3학년·학점 3.4라 지원 조건(3·4학년, 학점 3.0 이상)을 모두 갖췄어요. "
                                + "메이크업디자인학과는 회사가 선호하는 전공('미용예술대학')에 들어가요. "
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
                // 강의제작(116)은 운영계획서가 '전공무관'이라 전공 무관 자리다(ADR-0025)
                .andExpect(jsonPath("$.items[3].jobId").value(116))
                .andExpect(jsonPath("$.items[3].reasonTemplate").value("3학년이라 지원 조건(3·4학년)을 모두 갖췄어요. 전공을 따지지 않는 자리예요."))
                // 선호 전공 밖인 세정 SNS·영상은 내 학과 이름으로 그 사실을 말한다
                .andExpect(jsonPath("$.items[4].jobId").value(102))
                .andExpect(jsonPath("$.items[4].reasonTemplate").value(
                        "3학년이라 지원 조건(3·4학년)을 모두 갖췄어요. "
                                + "메이크업디자인학과는 회사가 선호하는 전공에는 없지만, 선호 전공은 지원 자격과는 상관없어요. "
                                + "관심 분야와 직무 내용이 가까워요."))
                .andExpect(jsonPath("$.blockedBy").isEmpty())
                .andReturn().getResponse().getContentAsString();
        Contract.assertSameShape(body, Contract.responseExample("getRecommendations", 200, null));

        List<Integer> ranks = JsonPath.read(body, "$.items[*].rank");
        assertThat(ranks).containsExactly(1, 2, 3, 4, 5);
        List<String> verdicts = JsonPath.read(body, "$.items[*].verdict");
        assertThat(verdicts).containsOnly("ELIGIBLE");
        // 선호 전공이 맞는 3직무(120·122·123)가 상위 3개. 4학년만 받는 비욘드(134)는 지원 불가라 빠진다(ADR-0024)
        List<Integer> top3 = JsonPath.read(body, "$.items[0:3].jobId");
        assertThat(top3).containsExactlyInAnyOrder(120, 122, 123);
        assertThat(JsonPath.<List<Integer>>read(body, "$.items[*].jobId")).doesNotContain(134);
        assertThat(body).doesNotContain("score");
        List<String> quotes = JsonPath.read(body, "$.items[*].citations[*].quote");
        assertThat(quotes).noneMatch(q -> q.startsWith("*"));
    }

    @Test
    void 기준일에_마감된_직무는_추천에서_빠진다() throws Exception {
        // 광고홍보콘텐츠학과(10) 4학년: 선호 전공이 맞는 101 AE는 7/18 센터 모집마감이라 기준일(7/23)에 빠진다(ADR-0016)
        String body = recommend(guestToken("STUDENT"), """
                {"profile": {"departmentId": 10, "grade": 4, "completedSemesters": 7, "gpa": 4.0,
                 "graduationExpected": false, "interestText": "광고 캠페인 기획", "homeAreaCode": null}}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(5))
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<Integer>>read(body, "$.items[*].jobId")).doesNotContain(101);
    }

    @Test
    void 전부_지원_불가면_items는_비고_blockedBy에_막은_요건별_직무_수() throws Exception {
        // 2학년·3학기: 이수 학기로 40개 모두 막히고, 학년(3학년 이상) 40 · 학점 하한 3.5·3.6 5 · 필수 자격증 1도 함께 센다(ADR-0024)
        String body = recommend(guestToken("STUDENT"), profile(2, 3, "null"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(body, "$.blockedBy[*].item")).containsExactly("이수 학기", "학년", "학점", "자격증");
        assertThat(JsonPath.<List<Integer>>read(body, "$.blockedBy[*].count")).containsExactly(40, 40, 5, 1);
    }

    @Test
    void 자격증은_답하지_않아도_없음으로_보고_blockedBy에_센다() throws Exception {
        // ADR-0021·0024: certificates가 null이든 []이든 미용 시술 보조는 자격증으로 막힌다
        for (String certificates : new String[] {"null", "[]"}) {
            String body = recommend(guestToken("STUDENT"), """
                    {"profile": {"departmentId": 43, "grade": 2, "completedSemesters": 3, "gpa": 3.4,
                     "graduationExpected": false, "interestText": null, "homeAreaCode": null, "certificates": %s}}"""
                    .formatted(certificates))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertThat(JsonPath.<List<String>>read(body, "$.blockedBy[*].item")).contains("자격증");
        }
    }

    @Test
    void 선호_전공도_관심도_맞지_않는_직무는_추천하지_않아_5개보다_적을_수_있다() throws Exception {
        // ADR-0022: 컴퓨터공학과(26)는 선호 전공이 맞는 직무가 미디어 코퍼스(136)와 전공 무관인 강의제작(116, ADR-0025)이고,
        // '백엔드 개발자'는 선도소프트 소프트웨어 개발(119, 학점 3.5 이상)과만 겹친다. 미용 시술 보조 등은 더 채우지 않는다
        String body = recommend(guestToken("STUDENT"), """
                {"profile": {"departmentId": 26, "grade": 3, "completedSemesters": 5, "gpa": 4.0,
                 "graduationExpected": false, "interestText": "백엔드 개발자", "homeAreaCode": null}}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.blockedBy").isEmpty())
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<Integer>>read(body, "$.items[*].jobId")).containsExactlyInAnyOrder(116, 119, 136);

        // 학점이 하한(3.5)보다 낮으면 119는 지원 불가라 빠지고, 가장 많이 겹친 직무가 빠졌다고 덜 겹친 마케팅 직무가
        // 관심 분야 이유로 들어오지도 않는다(관심 유사도는 회차 직무 전부로 잰다, ADR-0024)
        String lowGpa = recommend(guestToken("STUDENT"), """
                {"profile": {"departmentId": 26, "grade": 3, "completedSemesters": 5, "gpa": 3.4,
                 "graduationExpected": false, "interestText": "백엔드 개발자", "homeAreaCode": null}}""")
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<Integer>>read(lowGpa, "$.items[*].jobId")).containsExactly(136, 116);

        // 선호 전공이 맞는 직무가 없는 학과(무용예술학부 53)가 관심 분야를 비우면 전공 무관 자리(116 강의제작)만 — ADR-0025.
        // 2026-2에서 '관심 분야'로 막혀 0개가 되는 학생은 없다(전공 무관 자리가 3·4학년이면 늘 지원 가능이라서)
        String none = recommend(guestToken("STUDENT"), """
                {"profile": {"departmentId": 53, "grade": 3, "completedSemesters": 5, "gpa": 4.5,
                 "graduationExpected": false, "interestText": null, "homeAreaCode": null, "certificates": []}}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blockedBy").isEmpty())
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<Integer>>read(none, "$.items[*].jobId")).containsExactly(116);
    }

    @Test
    void 입력이_틀리면_400_센터는_403_토큰이_없으면_401() throws Exception {
        recommend(guestToken("STUDENT"), "{\"profile\": {\"departmentId\": 9999, \"grade\": 3, \"completedSemesters\": 5,"
                + " \"gpa\": 3.4, \"graduationExpected\": false}}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields[0].field").value("profile.departmentId"));
        recommend(guestToken("CENTER"), profile(3, 5, "null"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_ROLE"));
        mvc.perform(post("/api/recommendations").contentType(MediaType.APPLICATION_JSON).content(profile(3, 5, "null")))
                .andExpect(status().isUnauthorized());
    }

    private ResultActions recommend(String token, String body) throws Exception {
        return mvc.perform(post("/api/recommendations").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static String profile(int grade, int semesters, String interest) {
        return """
                {"profile": {"departmentId": 43, "grade": %d, "completedSemesters": %d, "gpa": 3.4,
                 "graduationExpected": false, "interestText": %s, "homeAreaCode": null}}""".formatted(grade, semesters, interest);
    }

    private String guestToken(String role) throws Exception {
        String body = mvc.perform(post("/api/auth/guest").header("CF-Connecting-IP", "198.51.100." + (100 + IP.getAndIncrement()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"" + role + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }
}
