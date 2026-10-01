package kr.ac.skuniv.coopradar.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import kr.ac.skuniv.coopradar.TestcontainersConfiguration;
import kr.ac.skuniv.coopradar.contract.Contract;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** docs/api #19~#22 지망. 2026-2 실제 시드의 직무(101~140)를 담는다. 응답은 계약 예시와 같은 모양이어야 한다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PlanApiTest {

    private static final AtomicInteger IP = new AtomicInteger(1);

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcClient db;

    @Test
    void 새로_담으면_201_다시_담으면_200이고_목록에_순위_없이_보인다() throws Exception {
        String token = memberToken();
        mvc.perform(get("/api/me/plan").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());

        add(token, 122).andExpect(status().isCreated()).andExpect(content().string(""));
        add(token, 122).andExpect(status().isOk()).andExpect(content().string(""));

        String body = mvc.perform(get("/api/me/plan").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].jobId").value(122))
                .andExpect(jsonPath("$.items[0].institution.id").value(7))
                .andExpect(jsonPath("$.items[0].rank").isEmpty())
                .andReturn().getResponse().getContentAsString();
        Contract.assertSameShape(body, Contract.responseExample("getMyPlan", 200, null));
        assertThat(OffsetDateTime.parse(JsonPath.read(body, "$.items[0].addedAt")).getOffset().getId()).isEqualTo("+09:00");
    }

    @Test
    void 순위는_전체를_바꾸고_빠진_직무는_순위가_지워진다() throws Exception {
        String token = memberToken();
        for (int job : new int[] {120, 122, 134, 101}) {
            add(token, job).andExpect(status().isCreated());
        }
        String body = ranks(token, "[{\"jobId\": 122, \"rank\": 1}, {\"jobId\": 120, \"rank\": 2}]")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].jobId").value(contains(122, 120, 134, 101)))
                .andExpect(jsonPath("$.items[0].rank").value(1))
                .andExpect(jsonPath("$.items[1].rank").value(2))
                .andExpect(jsonPath("$.items[2].rank").isEmpty())
                .andReturn().getResponse().getContentAsString();
        Contract.assertSameShape(body, Contract.responseExample("setPlanRanks", 200, null));

        // 순위를 맞바꾸고 하나를 빼면, 뺀 직무는 순위가 지워진다
        ranks(token, "[{\"jobId\": 120, \"rank\": 1}, {\"jobId\": 134, \"rank\": 3}]")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].jobId").value(contains(120, 134, 122, 101)))
                .andExpect(jsonPath("$.items[1].rank").value(3))
                .andExpect(jsonPath("$.items[2].rank").isEmpty());
        ranks(token, "[]").andExpect(status().isOk()).andExpect(jsonPath("$.items[?(@.rank != null)]").isEmpty());
    }

    @Test
    void 순위가_범위_밖이거나_중복이거나_담지_않은_직무면_400_RANK_INVALID이고_바뀌지_않는다() throws Exception {
        String token = memberToken();
        for (int job : new int[] {120, 122, 134, 101}) {
            add(token, job);
        }
        ranks(token, "[{\"jobId\": 120, \"rank\": 1}]").andExpect(status().isOk());

        for (String bad : new String[] {
                "[{\"jobId\": 122, \"rank\": 4}]",
                "[{\"jobId\": 122, \"rank\": 0}]",
                "[{\"jobId\": 122, \"rank\": 1}, {\"jobId\": 134, \"rank\": 1}]",
                "[{\"jobId\": 122, \"rank\": 1}, {\"jobId\": 122, \"rank\": 2}]",
                "[{\"jobId\": 140, \"rank\": 1}]",
                "[{\"jobId\": 120, \"rank\": 1}, {\"jobId\": 122, \"rank\": 2}, {\"jobId\": 134, \"rank\": 3},"
                        + " {\"jobId\": 101, \"rank\": 3}]"}) {
            ranks(token, bad).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("RANK_INVALID"));
        }
        mvc.perform(get("/api/me/plan").header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.items[0].jobId").value(120))
                .andExpect(jsonPath("$.items[0].rank").value(1));

        mvc.perform(json(put("/api/me/plan/ranks").header("Authorization", bearer(token)), "{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        ranks(token, "[{\"jobId\": 120}]")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    void 담기_취소는_204_담지_않았으면_404_없는_직무를_담으면_400() throws Exception {
        String token = memberToken();
        add(token, 101).andExpect(status().isCreated());
        mvc.perform(delete("/api/me/plan/items/101").header("Authorization", bearer(token)))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/api/me/plan/items/101").header("Authorization", bearer(token)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PLAN_ITEM_NOT_FOUND"));

        add(token, 999999)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.fields[0].field").value("jobId"));
        mvc.perform(json(post("/api/me/plan/items").header("Authorization", bearer(token)), "{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields[0].field").value("jobId"));
    }

    @Test
    void 담은_목록은_계정마다_따로이고_탈퇴하면_같이_지워진다() throws Exception {
        String a = guestToken("STUDENT");
        String b = memberToken();
        add(a, 120).andExpect(status().isCreated());
        mvc.perform(get("/api/me/plan").header("Authorization", bearer(b))).andExpect(jsonPath("$.items").isEmpty());

        long userId = ((Number) JsonPath.read(mvc.perform(get("/api/me").header("Authorization", bearer(a)))
                .andReturn().getResponse().getContentAsString(), "$.id")).longValue();
        mvc.perform(delete("/api/me").header("Authorization", bearer(a))).andExpect(status().isNoContent());
        assertThat(db.sql("SELECT count(*) FROM plan_item WHERE user_id = :id").param("id", userId)
                .query(Long.class).single()).isZero();
    }

    @Test
    void 센터는_403_토큰이_없으면_401() throws Exception {
        String center = guestToken("CENTER");
        mvc.perform(get("/api/me/plan").header("Authorization", bearer(center)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_ROLE"));
        add(center, 101).andExpect(status().isForbidden());
        mvc.perform(get("/api/me/plan")).andExpect(status().isUnauthorized());
    }

    // ───────── 도우미 ─────────

    private ResultActions add(String token, int jobId) throws Exception {
        return mvc.perform(json(post("/api/me/plan/items").header("Authorization", bearer(token)),
                "{\"jobId\": " + jobId + "}"));
    }

    private ResultActions ranks(String token, String ranks) throws Exception {
        return mvc.perform(json(put("/api/me/plan/ranks").header("Authorization", bearer(token)),
                "{\"ranks\": " + ranks + "}"));
    }

    private String memberToken() throws Exception {
        String body = mvc.perform(json(post("/api/auth/signup"),
                        "{\"email\":\"plan." + UUID.randomUUID() + "@example.com\",\"password\":\"password-8\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }

    private String guestToken(String role) throws Exception {
        String body = mvc.perform(json(post("/api/auth/guest").header("CF-Connecting-IP", "198.18.0." + IP.getAndIncrement()),
                        "{\"role\":\"" + role + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
