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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * docs/api #23 지망 점검. 2026-2 실제 시드로 시연 흐름을 본다 — 예시 학생이 소서 국내 마케팅(122)을 1지망,
 * AMD(120)를 2지망으로 두면 7/23 기준 같은 기관 해외 마케팅(123, 관심 0)이 첫 대안으로 나온다.
 * 관심 = 내 지망에 담은 사람(가상 + 실제, 본인 제외 — ADR-0019). 테스트마다 담은 지망을 비워 다른 테스트의 영향을 없앤다.
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

    @Autowired
    JdbcClient db;

    @BeforeEach
    void 담은_지망을_비운다() {
        db.sql("DELETE FROM plan_item").update();
    }

    @Test
    void 지망별_신호와_같은_기관_빈_자리를_주고_계약_모양과_같다() throws Exception {
        String token = guestToken();
        rankDemo(token);
        String body = check(token, "{\"profile\": " + PROFILE + "}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.asOf").value("2026-07-23"))
                .andExpect(jsonPath("$.isVirtual").value(true))
                .andExpect(jsonPath("$.signalSource").value("REPLAY"))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].rank").value(1))
                .andExpect(jsonPath("$.items[0].jobId").value(122))
                .andExpect(jsonPath("$.items[0].signal.interest").value(2))
                .andExpect(jsonPath("$.items[0].signal.liveInterest").value(0)) // 본인이 담은 것은 빼고 센다
                .andExpect(jsonPath("$.items[0].signal.status").value("OPEN")) // 정원에 닿아도 몰림 표시 없음
                .andExpect(jsonPath("$.items[1].jobId").value(120))
                .andExpect(jsonPath("$.items[1].signal.interest").value(2))
                .andExpect(jsonPath("$.items[1].signal.expectedFullOn").doesNotExist()) // 7/23에는 이미 정원(2/2)
                .andExpect(jsonPath("$.alternatives[0].jobId").value(123))
                .andExpect(jsonPath("$.alternatives[0].fit").value("HIGH"))
                .andExpect(jsonPath("$.alternatives[0].remaining").value(2))
                .andExpect(jsonPath("$.alternatives[0].why").value("1지망과 같은 기관의 직무이고, 지금 담은 사람이 0명이에요."))
                .andReturn().getResponse().getContentAsString();
        Contract.assertSameShape(body, Contract.responseExample("checkPlan", 200, null));

        List<Integer> alternatives = JsonPath.read(body, "$.alternatives[*].jobId");
        assertThat(alternatives).hasSizeLessThanOrEqualTo(5).doesNotContain(122, 120, 101); // 지망·마감 직무는 빠진다
        List<String> statuses = JsonPath.read(body, "$.alternatives[*].signal.status");
        assertThat(statuses).containsOnly("OPEN");
        List<Integer> remaining = JsonPath.read(body, "$.alternatives[*].remaining");
        assertThat(remaining).allSatisfy(r -> assertThat(r).isPositive());
        // ADR-0016: 대안은 ELIGIBLE만. 1지망 기관이 아니면 관심 문장과 겹칠 때만 '관심 분야와 가깝고',
        // 겹치지 않으면 '지원 조건을 모두 통과했고'(ADR-0022 — 가깝지 않은데 가깝다고 말하지 않는다)
        List<String> verdicts = JsonPath.read(body, "$.alternatives[*].verdict");
        assertThat(verdicts).containsOnly("ELIGIBLE");
        List<String> whys = JsonPath.read(body, "$.alternatives[1:].why");
        assertThat(whys).allSatisfy(w -> assertThat(w).matches(
                "(1지망과 같은 기관의 직무이고|관심 분야와 가깝고|지원 조건을 모두 통과했고), "
                        + "(지금 담은 사람이 0명이에요\\.|남은 자리가 \\d+개예요\\.)"));
        assertThat(whys).anyMatch(w -> w.startsWith("관심 분야와 가깝고"))
                .anyMatch(w -> w.startsWith("지원 조건을 모두 통과했고"));
    }

    @Test
    void 기준일을_바꾸면_신호가_바뀌고_기간_밖이면_400_AS_OF_OUT_OF_RANGE() throws Exception {
        String token = guestToken();
        rankDemo(token);
        check(token, "{\"profile\": " + PROFILE + ", \"asOf\": \"2026-07-13\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.asOf").value("2026-07-13"))
                .andExpect(jsonPath("$.items[0].signal.interest").value(0));
        // 7/18에는 AMD가 1/2라 정원 도달 예상일이 있다
        check(token, "{\"profile\": " + PROFILE + ", \"asOf\": \"2026-07-18\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[1].jobId").value(120))
                .andExpect(jsonPath("$.items[1].signal.expectedFullOn").value("2026-07-21"));
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
    void 순위를_안_정했으면_items는_비고_대안만_준다_담은_직무는_대안에서_빠진다_센터는_403() throws Exception {
        String token = guestToken();
        check(token, "{\"profile\": " + PROFILE + "}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.alternatives.length()").value(5))
                .andExpect(jsonPath("$.alternatives[0].jobId").value(123))
                .andExpect(jsonPath("$.alternatives[0].why").value("관심 분야와 가깝고, 지금 담은 사람이 0명이에요."));
        // 순위 없이 담기만 해도 대안에서 빠진다
        mvc.perform(post("/api/me/plan/items").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"jobId\": 123}"));
        String body = check(token, "{\"profile\": " + PROFILE + "}").andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<Integer>>read(body, "$.alternatives[*].jobId")).doesNotContain(123);
        String center = JsonPath.read(mvc.perform(post("/api/auth/guest").header("CF-Connecting-IP", "203.0.113.250")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"CENTER\"}"))
                .andReturn().getResponse().getContentAsString(), "$.accessToken");
        check(center, "{\"profile\": " + PROFILE + "}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_ROLE"));
    }

    @Test
    void 다른_학생이_담은_수가_관심에_더해지고_본인_것은_빠진다() throws Exception {
        String me = guestToken();
        rankDemo(me);
        String other = guestToken();
        add(other, 123); // 순위 없이 담기만 해도 관심이다
        add(other, 122);

        String body = check(me, "{\"profile\": " + PROFILE + "}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].jobId").value(122))
                .andExpect(jsonPath("$.items[0].signal.interest").value(3)) // 가상 2 + 다른 학생 1
                .andExpect(jsonPath("$.items[0].signal.liveInterest").value(1))
                .andExpect(jsonPath("$.items[0].signal.ratio").value(1.5))
                .andExpect(jsonPath("$.alternatives[0].jobId").value(123))
                .andExpect(jsonPath("$.alternatives[0].signal.interest").value(1))
                .andExpect(jsonPath("$.alternatives[0].signal.liveInterest").value(1))
                .andExpect(jsonPath("$.alternatives[0].remaining").value(1))
                .andExpect(jsonPath("$.alternatives[0].why").value("1지망과 같은 기관의 직무이고, 남은 자리가 1개예요."))
                .andReturn().getResponse().getContentAsString();
        Contract.assertSameShape(body, Contract.responseExample("checkPlan", 200, null));

        // 기준일(7/23)보다 앞 날짜에는 실제 담은 수가 없다
        check(me, "{\"profile\": " + PROFILE + ", \"asOf\": \"2026-07-22\"}")
                .andExpect(jsonPath("$.items[0].signal.interest").value(2))
                .andExpect(jsonPath("$.items[0].signal.liveInterest").value(0));

        // 상대 쪽에서 보면 내가 담은 122·120이 보이고, 자기가 담은 것은 빠진다
        mvc.perform(put("/api/me/plan/ranks").header("Authorization", "Bearer " + other)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ranks\": [{\"jobId\": 122, \"rank\": 1}, {\"jobId\": 123, \"rank\": 2}]}"))
                .andExpect(status().isOk());
        check(other, "{\"profile\": " + PROFILE + "}")
                .andExpect(jsonPath("$.items[0].jobId").value(122))
                .andExpect(jsonPath("$.items[0].signal.interest").value(3)) // 가상 2 + 나 1
                .andExpect(jsonPath("$.items[1].jobId").value(123))
                .andExpect(jsonPath("$.items[1].signal.interest").value(0))
                .andExpect(jsonPath("$.items[1].signal.liveInterest").value(0));
    }

    // ───────── 도우미 ─────────

    private void add(String token, int jobId) throws Exception {
        mvc.perform(post("/api/me/plan/items").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"jobId\": " + jobId + "}"))
                .andExpect(status().isCreated());
    }

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
