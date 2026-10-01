package kr.ac.skuniv.coopradar.center;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
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
 * docs/api #24 센터 현황판. 2026-2 실제 시드(R__seed.sql)로 본다. 기대 숫자는 seed.json으로 같은 규칙을
 * 파이썬에서 따로 계산해 맞춘 값이다(10/1). NARROW_POOL 기준은 기본값 200명.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CenterApiTest {

    private static final AtomicInteger IP = new AtomicInteger(1);

    @Autowired
    MockMvc mvc;

    @Test
    void 기본_기준일_7월_18일의_현황판은_계약_모양과_같다() throws Exception {
        String body = board(guestToken("CENTER"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.asOf").value("2026-07-18"))
                .andExpect(jsonPath("$.isVirtual").value(true))
                .andExpect(jsonPath("$.signalSource").value("REPLAY"))
                .andExpect(jsonPath("$.round.termCode").value("2026-2"))
                .andExpect(jsonPath("$.summary.jobs").value(40))
                .andExpect(jsonPath("$.summary.seats").value(59))
                .andExpect(jsonPath("$.summary.intentTotal").value(11))
                .andExpect(jsonPath("$.summary.zeroSignalJobs").value(32))
                .andExpect(jsonPath("$.summary.closedJobs").value(1))
                .andExpect(jsonPath("$.historyAvailable").value(false))
                .andExpect(jsonPath("$.rows.length()").value(40))
                .andExpect(jsonPath("$.alerts.length()").value(12))
                .andReturn().getResponse().getContentAsString();
        Contract.assertSameShape(body, Contract.responseExample("getCenterBoard", 200, null));

        // 101: 7/18 센터 모집마감 → CLOSED, 광고홍보콘텐츠학과 115명 → 대상 학과 좁음, 포트폴리오 필수
        Map<String, Object> r101 = row(body, 101);
        assertThat(JsonPath.<String>read(r101, "$.signal.status")).isEqualTo("CLOSED");
        assertThat(JsonPath.<Integer>read(r101, "$.signal.intent")).isEqualTo(2);
        assertThat(JsonPath.<Integer>read(r101, "$.eligiblePool")).isEqualTo(115);
        assertThat(JsonPath.<List<String>>read(r101, "$.risks[*].code"))
                .containsExactly("NARROW_POOL", "PORTFOLIO_REQUIRED");
        assertThat(JsonPath.<String>read(r101, "$.risks[0].label")).isEqualTo("대상 학과가 좁음");
        assertThat(JsonPath.<String>read(r101, "$.risks[0].detail")).isEqualTo("선호 전공 재학생 115명");

        // 120: 최근 3일 추세로 7/21에 정원 도달 예상
        assertThat(JsonPath.<String>read(row(body, 120), "$.signal.expectedFullOn")).isEqualTo("2026-07-21");
        // 140: 토요일 실습
        assertThat(JsonPath.<List<String>>read(row(body, 140), "$.risks[*].code")).contains("WEEKEND");
        // 116: 직무 알림 2건 / 102: 세정 기관 전체 알림 1건
        assertThat(JsonPath.<Integer>read(row(body, 116), "$.alertCount")).isEqualTo(2);
        assertThat(JsonPath.<String>read(row(body, 116), "$.risks[-1].detail")).isEqualTo("검토 알림 2건");
        assertThat(JsonPath.<Integer>read(row(body, 102), "$.alertCount")).isEqualTo(1);

        List<String> narrow = JsonPath.read(body, "$.rows[?(@.eligiblePool < 200)].jobId");
        assertThat(narrow).hasSize(8);
        List<Integer> withNarrow = JsonPath.read(body, "$.rows[?('NARROW_POOL' in @.risks[*].code)].jobId");
        assertThat(withNarrow).hasSize(8);
    }

    @Test
    void 모집_마지막_날에는_지원_의사_합이_최종_배정_수와_같다() throws Exception {
        board(guestToken("CENTER"), "2026-07-24")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.asOf").value("2026-07-24"))
                .andExpect(jsonPath("$.summary.intentTotal").value(21))
                .andExpect(jsonPath("$.summary.closedJobs").value(3));
        board(guestToken("CENTER"), "2026-07-13")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.intentTotal").value(0))
                .andExpect(jsonPath("$.summary.zeroSignalJobs").value(40));
    }

    @Test
    void 기준일이_모집기간_밖이면_400_AS_OF_OUT_OF_RANGE_형식이_틀리면_INVALID_INPUT() throws Exception {
        String token = guestToken("CENTER");
        board(token, "2026-07-25")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AS_OF_OUT_OF_RANGE"));
        board(token, "2026-07-12")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AS_OF_OUT_OF_RANGE"));
        board(token, "7월18일")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    void 학생은_403_토큰이_없으면_401() throws Exception {
        board(guestToken("STUDENT"), null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_ROLE"));
        mvc.perform(get("/api/center/board"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
    }

    // ───────── 도우미 ─────────

    private ResultActions board(String token, String asOf) throws Exception {
        var request = get("/api/center/board").header("Authorization", "Bearer " + token);
        if (asOf != null) {
            request.param("asOf", asOf);
        }
        return mvc.perform(request);
    }

    private static Map<String, Object> row(String body, int jobId) {
        List<Map<String, Object>> rows = JsonPath.read(body, "$.rows[?(@.jobId == " + jobId + ")]");
        assertThat(rows).hasSize(1);
        return rows.getFirst();
    }

    private String guestToken(String role) throws Exception {
        String body = mvc.perform(post("/api/auth/guest").header("CF-Connecting-IP", "192.0.2." + (200 + IP.getAndIncrement()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"" + role + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }
}
