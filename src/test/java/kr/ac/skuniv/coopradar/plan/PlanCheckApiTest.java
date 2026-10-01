package kr.ac.skuniv.coopradar.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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

/**
 * docs/api #23 지망 점검. 2026-2 실제 시드로 시연 흐름을 본다 — 예시 학생이 소서 국내 마케팅(122)을 1지망,
 * AMD(120)를 2지망으로 두면 7/18 기준 같은 기관 해외 마케팅(123, 지원 의사 0)이 첫 대안으로 나온다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PlanCheckApiTest {

    private static final AtomicInteger IP = new AtomicInteger(1);
    private static final String PROFILE = """
            {"departmentId": 43, "grade": 3, "completedSemesters": 5, "gpa": 3.4, "graduationExpected": false,
             "interestText": "뷰티 브랜드 SNS 마케팅", "homeAreaCode": null}""";

    @Autowired
    MockMvc mvc;

    @Test
    void 지망별_신호와_같은_기관_빈_자리를_주고_계약_모양과_같다() throws Exception {
        String token = guestToken();
        rankDemo(token);
        String body = check(token, "{\"profile\": " + PROFILE + "}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.asOf").value("2026-07-18"))
                .andExpect(jsonPath("$.isVirtual").value(true))
                .andExpect(jsonPath("$.signalSource").value("REPLAY"))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].rank").value(1))
                .andExpect(jsonPath("$.items[0].jobId").value(122))
                .andExpect(jsonPath("$.items[0].signal.intent").value(2))
                .andExpect(jsonPath("$.items[0].signal.status").value("OPEN")) // 정원에 닿아도 몰림 표시 없음
                .andExpect(jsonPath("$.items[1].jobId").value(120))
                .andExpect(jsonPath("$.items[1].signal.expectedFullOn").value("2026-07-21"))
                .andExpect(jsonPath("$.alternatives[0].jobId").value(123))
                .andExpect(jsonPath("$.alternatives[0].fit").value("HIGH"))
                .andExpect(jsonPath("$.alternatives[0].remaining").value(2))
                .andExpect(jsonPath("$.alternatives[0].why").value("지망한 직무와 같은 기관이고, 지금 지원 의사가 0명이에요."))
                .andReturn().getResponse().getContentAsString();
        Contract.assertSameShape(body, Contract.responseExample("checkPlan", 200, null));

        List<Integer> alternatives = JsonPath.read(body, "$.alternatives[*].jobId");
        assertThat(alternatives).hasSizeLessThanOrEqualTo(5).doesNotContain(122, 120, 101); // 지망·마감 직무는 빠진다
        List<String> statuses = JsonPath.read(body, "$.alternatives[*].signal.status");
        assertThat(statuses).containsOnly("OPEN");
        List<Integer> remaining = JsonPath.read(body, "$.alternatives[*].remaining");
        assertThat(remaining).allSatisfy(r -> assertThat(r).isPositive());
        List<String> verdicts = JsonPath.read(body, "$.alternatives[*].verdict");
        assertThat(verdicts).doesNotContain("INELIGIBLE");
    }

    @Test
    void 기준일을_바꾸면_신호가_바뀌고_기간_밖이면_400_AS_OF_OUT_OF_RANGE() throws Exception {
        String token = guestToken();
        rankDemo(token);
        check(token, "{\"profile\": " + PROFILE + ", \"asOf\": \"2026-07-13\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.asOf").value("2026-07-13"))
                .andExpect(jsonPath("$.items[0].signal.intent").value(0));
        check(token, "{\"profile\": " + PROFILE + ", \"asOf\": \"2026-07-30\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AS_OF_OUT_OF_RANGE"));
        check(token, "{\"profile\": " + PROFILE + ", \"asOf\": \"어제\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        check(token, "{\"asOf\": \"2026-07-18\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields[0].field").value("profile"));
    }

    @Test
    void 순위를_안_정했으면_items는_비고_대안만_준다_센터는_403() throws Exception {
        String token = guestToken();
        check(token, "{\"profile\": " + PROFILE + "}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.alternatives.length()").value(5));
        String center = JsonPath.read(mvc.perform(post("/api/auth/guest").header("CF-Connecting-IP", "203.0.113.250")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"CENTER\"}"))
                .andReturn().getResponse().getContentAsString(), "$.accessToken");
        check(center, "{\"profile\": " + PROFILE + "}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_ROLE"));
    }

    // ───────── 도우미 ─────────

    private void rankDemo(String token) throws Exception {
        for (int job : new int[] {122, 120}) {
            mvc.perform(post("/api/me/plan/items").header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"jobId\": " + job + "}"));
        }
        mvc.perform(put("/api/me/plan/ranks").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ranks\": [{\"jobId\": 122, \"rank\": 1}, {\"jobId\": 120, \"rank\": 2}]}"))
                .andExpect(status().isOk());
    }

    private ResultActions check(String token, String body) throws Exception {
        return mvc.perform(post("/api/me/plan/check").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String guestToken() throws Exception {
        String body = mvc.perform(post("/api/auth/guest").header("CF-Connecting-IP", "198.18.1." + IP.getAndIncrement())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"STUDENT\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }
}
