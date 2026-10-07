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
 * docs/api #27 담은 직무 기준 빈 자리(ADR-0029). 2026-2 실제 시드, 예시 프로필, 기준일 7/23 —
 * 소서 국내 마케팅(122)을 담으면 관심 2 · 정원 2이고, 같은 기관 해외 마케팅(123, 관심 0)이 첫 제안이다.
 * 테스트마다 담은 지망을 비워 다른 테스트의 영향을 없앤다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PlanItemAlternativesApiTest {

    private static final AtomicInteger IP = new AtomicInteger(1);
    private static final String PROFILE = """
            {"profile": {"departmentId": 43, "grade": 3, "completedSemesters": 5, "gpa": 3.4,
             "graduationExpected": false, "interestText": "뷰티 브랜드 SNS 마케팅", "homeAreaCode": null}}""";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient db;

    @BeforeEach
    void 담은_지망을_비운다() {
        db.sql("DELETE FROM plan_item").update();
    }

    @Test
    void 담은_직무의_신호와_같은_기관_빈_자리를_주고_계약_모양과_같다() throws Exception {
        String token = guestToken();
        add(token, 122);
        String body = alternatives(token, 122, PROFILE)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.asOf").value("2026-07-23"))
                .andExpect(jsonPath("$.isVirtual").value(true))
                .andExpect(jsonPath("$.signalSource").value("REPLAY"))
                .andExpect(jsonPath("$.item.jobId").value(122))
                .andExpect(jsonPath("$.item.rank").isEmpty()) // 순위를 아직 안 정했다
                .andExpect(jsonPath("$.item.signal.interest").value(2)) // 본인이 담은 것은 빼고 센다
                .andExpect(jsonPath("$.item.signal.liveInterest").value(0))
                .andExpect(jsonPath("$.item.signal.headcount").value(2))
                .andExpect(jsonPath("$.item.signal.status").value("OPEN")) // 정원에 닿아도 몰림 상태 없음
                .andExpect(jsonPath("$.alternatives[0].jobId").value(123))
                .andExpect(jsonPath("$.alternatives[0].remaining").value(2))
                .andExpect(jsonPath("$.alternatives[0].why").value("방금 담은 직무와 같은 기관의 직무이고, 지금 담은 사람이 0명이에요."))
                .andReturn().getResponse().getContentAsString();
        Contract.assertSameShape(body, Contract.responseExample("getPlanItemAlternatives", 200, null));

        List<Integer> jobs = JsonPath.read(body, "$.alternatives[*].jobId");
        assertThat(jobs).hasSizeLessThanOrEqualTo(5).doesNotContain(122); // 기준 직무(담은 직무)는 빠진다
        List<String> verdicts = JsonPath.read(body, "$.alternatives[*].verdict");
        assertThat(verdicts).containsOnly("ELIGIBLE");
        List<String> whys = JsonPath.read(body, "$.alternatives[1:].why");
        assertThat(whys).allSatisfy(w -> assertThat(w).matches(
                "(방금 담은 직무와 같은 기관의 직무이고|관심 분야와 가깝고|지원 조건을 모두 통과했고), "
                        + "(지금 담은 사람이 0명이에요\\.|남은 자리가 \\d+개예요\\.)"));
    }

    @Test
    void 대안은_지망_점검과_같고_why의_같은_기관_기준만_다르다() throws Exception {
        String token = guestToken();
        add(token, 122);
        mvc.perform(put("/api/me/plan/ranks").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ranks\": [{\"jobId\": 122, \"rank\": 1}]}"))
                .andExpect(status().isOk());
        String item = alternatives(token, 122, PROFILE)
                .andExpect(jsonPath("$.item.rank").value(1))
                .andReturn().getResponse().getContentAsString();
        String check = mvc.perform(post("/api/me/plan/check").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(PROFILE))
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<Integer>>read(item, "$.alternatives[*].jobId"))
                .isEqualTo(JsonPath.<List<Integer>>read(check, "$.alternatives[*].jobId"));
        List<String> fromItem = JsonPath.read(item, "$.alternatives[*].why");
        List<String> fromCheck = JsonPath.read(check, "$.alternatives[*].why");
        assertThat(fromItem).isEqualTo(fromCheck.stream()
                .map(w -> w.replace("1지망과 같은 기관의 직무이고", "방금 담은 직무와 같은 기관의 직무이고")).toList());
    }

    @Test
    void 관심이_정원보다_적으면_신호로_알_수_있고_다른_사람이_담은_수는_더한다() throws Exception {
        String me = guestToken();
        add(me, 123);
        alternatives(me, 123, PROFILE)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.item.signal.interest").value(0)) // 0 < 정원 2 → 화면은 제안을 띄우지 않는다
                .andExpect(jsonPath("$.item.signal.headcount").value(2));
        String other = guestToken();
        add(other, 123);
        alternatives(me, 123, PROFILE)
                .andExpect(jsonPath("$.item.signal.interest").value(1))
                .andExpect(jsonPath("$.item.signal.liveInterest").value(1));
    }

    @Test
    void 담지_않은_직무는_404_본문이_없으면_400_센터는_403() throws Exception {
        String token = guestToken();
        alternatives(token, 122, PROFILE)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PLAN_ITEM_NOT_FOUND"));
        add(token, 122);
        alternatives(token, 122, "{\"asOf\": \"2026-07-18\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.fields[0].field").value("profile"));
        alternatives(token, 122, PROFILE.replace("}}", "}, \"asOf\": \"2026-07-30\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AS_OF_OUT_OF_RANGE"));
        String center = JsonPath.read(mvc.perform(post("/api/auth/guest").header("CF-Connecting-IP", "203.0.113.251")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"CENTER\"}"))
                .andReturn().getResponse().getContentAsString(), "$.accessToken");
        alternatives(center, 122, PROFILE)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_ROLE"));
    }

    // ───────── 도우미 ─────────

    private void add(String token, int jobId) throws Exception {
        mvc.perform(post("/api/me/plan/items").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"jobId\": " + jobId + "}"))
                .andExpect(status().isCreated());
    }

    private ResultActions alternatives(String token, int jobId, String body) throws Exception {
        return mvc.perform(post("/api/me/plan/items/" + jobId + "/alternatives").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String guestToken() throws Exception {
        String body = mvc.perform(post("/api/auth/guest").header("CF-Connecting-IP", "198.18.27." + IP.getAndIncrement())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"STUDENT\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }
}
