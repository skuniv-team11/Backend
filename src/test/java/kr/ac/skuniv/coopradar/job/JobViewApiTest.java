package kr.ac.skuniv.coopradar.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
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
 * docs/api #25 직무 조회수(ADR-0019). 학생이 직무 상세(#17)를 열면 계정마다 직무별로 하루 한 번 센다.
 * 테스트마다 조회 기록을 비운다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class JobViewApiTest {

    private static final AtomicInteger IP = new AtomicInteger(1);

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcClient db;

    @BeforeEach
    void 조회_기록을_비운다() {
        db.sql("DELETE FROM job_view").update();
    }

    @Test
    void 학생은_계정마다_하루_한_번만_세고_센터와_조회수_API는_세지_않는다() throws Exception {
        String a = guestToken("STUDENT");
        String b = guestToken("STUDENT");
        String center = guestToken("CENTER");

        String body = views(a, 122)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value(122))
                .andExpect(jsonPath("$.views").value(0))
                .andExpect(jsonPath("$.todayViews").value(0))
                .andReturn().getResponse().getContentAsString();
        Contract.assertSameShape(body, Contract.responseExample("getJobViews", 200, null));

        detail(a, 122).andExpect(status().isOk());
        detail(a, 122).andExpect(status().isOk()); // 같은 날 다시 열어도 그대로
        detail(b, 122).andExpect(status().isOk());
        detail(center, 122).andExpect(status().isOk()); // 센터는 세지 않는다
        detail(a, 123).andExpect(status().isOk());

        views(center, 122)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.views").value(2))
                .andExpect(jsonPath("$.todayViews").value(2));
        views(a, 123).andExpect(jsonPath("$.views").value(1));
        views(a, 101).andExpect(jsonPath("$.views").value(0));
    }

    @Test
    void 지난_날_기록은_views에만_들어가고_계정이_지워져도_조회수는_남는다() throws Exception {
        String a = guestToken("STUDENT");
        detail(a, 120).andExpect(status().isOk());
        db.sql("INSERT INTO job_view (job_id, user_id, viewed_on) VALUES (120, NULL, DATE '2026-07-20')").update();

        views(a, 120)
                .andExpect(jsonPath("$.views").value(2))
                .andExpect(jsonPath("$.todayViews").value(1));

        mvc.perform(delete("/api/me").header("Authorization", "Bearer " + a)).andExpect(status().isNoContent());
        assertThat(db.sql("SELECT count(*) FROM job_view WHERE job_id = 120 AND user_id IS NULL")
                .query(Integer.class).single()).isEqualTo(2);
        views(guestToken("CENTER"), 120).andExpect(jsonPath("$.views").value(2));
    }

    @Test
    void 없는_직무는_404_토큰이_없으면_401() throws Exception {
        views(guestToken("STUDENT"), 999999)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("JOB_NOT_FOUND"));
        mvc.perform(get("/api/jobs/122/views"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
        // 없는 직무 상세는 기록하지 않는다
        detail(guestToken("STUDENT"), 999999).andExpect(status().isNotFound());
        assertThat(db.sql("SELECT count(*) FROM job_view").query(Integer.class).single()).isZero();
    }

    // ───────── 도우미 ─────────

    private ResultActions detail(String token, int jobId) throws Exception {
        return mvc.perform(get("/api/jobs/" + jobId).header("Authorization", "Bearer " + token));
    }

    private ResultActions views(String token, int jobId) throws Exception {
        return mvc.perform(get("/api/jobs/" + jobId + "/views").header("Authorization", "Bearer " + token));
    }

    private String guestToken(String role) throws Exception {
        String body = mvc.perform(post("/api/auth/guest").header("CF-Connecting-IP", "198.51.100." + IP.getAndIncrement())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"" + role + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }
}
