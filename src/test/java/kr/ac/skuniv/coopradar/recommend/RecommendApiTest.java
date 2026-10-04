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
                .andExpect(jsonPath("$.items[0].reasonTemplate")
                        .value("관심 분야와 직무 내용이 가깝고, 선호 전공에 소속 학과가 들어 있어요."))
                .andExpect(jsonPath("$.items[0].citations[0].sourceType").value("TESTIMONIAL"))
                .andExpect(jsonPath("$.items[0].citations[1].sourceType").value("OPERATION_PLAN"))
                .andExpect(jsonPath("$.items[1].jobId").value(123))
                .andExpect(jsonPath("$.blockedBy").isEmpty())
                .andReturn().getResponse().getContentAsString();
        Contract.assertSameShape(body, Contract.responseExample("getRecommendations", 200, null));

        List<Integer> ranks = JsonPath.read(body, "$.items[*].rank");
        assertThat(ranks).containsExactly(1, 2, 3, 4, 5);
        List<String> verdicts = JsonPath.read(body, "$.items[*].verdict");
        assertThat(verdicts).doesNotContain("INELIGIBLE");
        // 선호 전공이 맞는 4직무(120·122·123·134)가 상위 4개
        List<Integer> top4 = JsonPath.read(body, "$.items[0:4].jobId");
        assertThat(top4).containsExactlyInAnyOrder(120, 122, 123, 134);
        assertThat(body).doesNotContain("score");
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
        recommend(guestToken("STUDENT"), profile(2, 3, "null"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.blockedBy.length()").value(1))
                .andExpect(jsonPath("$.blockedBy[0].item").value("이수 학기"))
                .andExpect(jsonPath("$.blockedBy[0].count").value(40));
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
