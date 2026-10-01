package kr.ac.skuniv.coopradar.commute;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import kr.ac.skuniv.coopradar.TestcontainersConfiguration;
import kr.ac.skuniv.coopradar.contract.Contract;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * docs/api #18 통근 조회. 카카오 대신 로컬 가짜 서버를 띄워 실제 HTTP 호출·해석 경로를 그대로 탄다.
 * 응답은 계약 예시(commute.json · commute-unavailable.json)와 같은 모양이어야 한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CommuteApiTest {

    // 시드 대신 넣는 가상 행. 실제 시드 id와 겹치지 않게 9000번대
    private static final long JOB = 9101;
    private static final long JOB_NO_COORD = 9102;
    private static final String OK_BODY = """
            {"status":"OK","properties":{"total":2},"routes":[
              {"properties":{"type":"BUS_AND_SUBWAY","totalDistance":15000,"totalTime":2580,"transfers":1,
                             "fare":{"value":1550,"min":1550,"max":1550}},"steps":[]},
              {"properties":{"type":"BUS","totalDistance":14000,"totalTime":3000,"transfers":0,
                             "fare":{"value":1500,"min":1500,"max":1500}},"steps":[]}]}""";

    /** 가짜 카카오: 다음 응답과 마지막 요청을 들고 있는다. */
    record Reply(int status, String body, long delayMillis) {
    }

    private static final AtomicReference<Reply> REPLY = new AtomicReference<>();
    private static final AtomicReference<String> LAST_QUERY = new AtomicReference<>();
    private static final AtomicReference<String> LAST_AUTH = new AtomicReference<>();
    private static final AtomicInteger CALLS = new AtomicInteger();
    private static final HttpServer KAKAO = startKakao();

    @DynamicPropertySource
    static void kakao(DynamicPropertyRegistry r) {
        r.add("app.commute.kakao-url", () -> "http://127.0.0.1:" + KAKAO.getAddress().getPort());
        r.add("app.commute.kakao-key", () -> "test-key");
        r.add("app.commute.timeout", () -> "500ms");
        r.add("app.commute.per-user-per-hour", () -> "3");
    }

    @AfterAll
    static void stop() {
        KAKAO.stop(0);
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcClient db;

    @BeforeEach
    void 시드() {
        REPLY.set(new Reply(200, OK_BODY, 0));
        LAST_QUERY.set(null);
        LAST_AUTH.set(null);
        CALLS.set(0);
        db.sql("INSERT INTO area (code, sido, name, lat, lng, sort_order) VALUES ('11350', '서울', '노원구', 37.654, 127.056, 9001) "
                + "ON CONFLICT DO NOTHING").update();
        db.sql("INSERT INTO program (id, code, name) VALUES (9001, 'TEST_COMMUTE', '통근 테스트 사업') ON CONFLICT DO NOTHING").update();
        db.sql("INSERT INTO recruit_round (id, program_id, term_code, round_no) VALUES (9001, 9001, '2026-2', 9) "
                + "ON CONFLICT DO NOTHING").update();
        db.sql("INSERT INTO institution (id, name, size, listing) VALUES (9101, '통근테스트 (가상)', 'SME', 'UNLISTED') "
                + "ON CONFLICT DO NOTHING").update();
        db.sql("INSERT INTO workplace (id, institution_id, address, lat, lng, coord_source) "
                + "VALUES (9101, 9101, '서울 성동구 (가상)', 37.5633, 127.0371, 'MANUAL') ON CONFLICT DO NOTHING").update();
        db.sql("INSERT INTO workplace (id, institution_id, address) VALUES (9102, 9101, '서울 중구 (가상, 좌표 없음)') "
                + "ON CONFLICT DO NOTHING").update();
        job(JOB, 9101, 1);
        job(JOB_NO_COORD, 9102, 2);
    }

    private void job(long id, int workplaceId, int seq) {
        db.sql("""
                        INSERT INTO job (id, round_id, institution_id, workplace_id, list_seq, team, title, course, job_type,
                                         overtime, stipend_basis, headcount, grade_rule, portfolio, certificate)
                        VALUES (:id, 9001, 9101, :wp, :seq, '테스트팀', '통근 테스트 직무', 'SEMESTER', 'EXPERIENCE',
                                'NONE', 'MONTHLY', 1, 'Y3_4', 'NONE', 'NONE')
                        ON CONFLICT DO NOTHING""")
                .param("id", id).param("wp", workplaceId).param("seq", seq).update();
    }

    // ───────── 정상 ─────────

    @Test
    void 사는_곳에서_근로지까지_첫_경로로_답하고_계약_모양() throws Exception {
        String body = commute(JOB, guestToken(), "{\"homeAreaCode\":\"11350\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value(JOB))
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.origin.type").value("HOME_AREA"))
                .andExpect(jsonPath("$.origin.areaCode").value("11350"))
                .andExpect(jsonPath("$.origin.label").value("노원구"))
                .andExpect(jsonPath("$.destination.address").value("서울 성동구 (가상)"))
                .andExpect(jsonPath("$.minutes").value(43))      // 2580초 ÷ 60
                .andExpect(jsonPath("$.transfers").value(1))
                .andExpect(jsonPath("$.fareWon").value(1550))
                .andExpect(jsonPath("$.provider").value("KAKAO_MAP"))
                .andExpect(jsonPath("$.unavailableReason").isEmpty())
                .andReturn().getResponse().getContentAsString();
        Contract.assertSameShape(body, Contract.responseExample("getCommute", 200, "commute.json"));
        assertThat(OffsetDateTime.parse(JsonPath.read(body, "$.queriedAt")).getOffset().getId()).isEqualTo("+09:00");

        // 카카오에는 x = 경도, y = 위도. 키는 KakaoAK 헤더로
        assertThat(LAST_QUERY.get()).contains("start_x=127.056", "start_y=37.654", "end_x=127.0371", "end_y=37.5633");
        assertThat(LAST_AUTH.get()).isEqualTo("KakaoAK test-key");
    }

    @Test
    void 본문이_없거나_사는_곳이_null이면_서경대에서_출발() throws Exception {
        String token = guestToken();
        mvc.perform(post("/api/jobs/" + JOB + "/commute").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.origin.type").value("SCHOOL"))
                .andExpect(jsonPath("$.origin.areaCode").isEmpty())
                .andExpect(jsonPath("$.origin.label").value("서경대"))
                .andExpect(jsonPath("$.available").value(true));
        assertThat(LAST_QUERY.get()).contains("start_x=127.0131", "start_y=37.615");

        commute(JOB, token, "{\"homeAreaCode\":null}")
                .andExpect(jsonPath("$.origin.type").value("SCHOOL"));
    }

    // ───────── 실패해도 200 ─────────

    @Test
    void 근무지_좌표가_없으면_카카오를_부르지_않고_NO_WORKPLACE() throws Exception {
        String body = commute(JOB_NO_COORD, guestToken(), "{\"homeAreaCode\":\"11350\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.unavailableReason").value("NO_WORKPLACE"))
                .andExpect(jsonPath("$.minutes").isEmpty())
                .andExpect(jsonPath("$.destination.address").value("서울 중구 (가상, 좌표 없음)"))
                .andReturn().getResponse().getContentAsString();
        Contract.assertSameShape(body, Contract.responseExample("getCommute", 200, "commute-unavailable.json"));
        assertThat(CALLS.get()).isZero();
    }

    @Test
    void 카카오가_경로를_못_찾거나_실패하거나_느리면_200과_이유() throws Exception {
        String token = guestToken();
        REPLY.set(new Reply(200, "{\"status\":\"NO_RESULTS\"}", 0));
        unavailable(token, "NO_ROUTE");

        REPLY.set(new Reply(500, "{\"errorType\":\"InternalServerError\"}", 0));
        unavailable(token, "PROVIDER_ERROR");

        REPLY.set(new Reply(200, OK_BODY, 1500)); // 제한 시간(테스트 500ms)을 넘김
        unavailable(token, "PROVIDER_ERROR");

        String other = guestToken();
        REPLY.set(new Reply(400, "{\"code\":-10,\"msg\":\"API limit has been exceeded.\"}", 0));
        unavailable(other, "LIMITED");
    }

    @Test
    void 계정당_한도를_넘으면_카카오를_부르지_않고_LIMITED() throws Exception {
        String token = guestToken();
        for (int i = 0; i < 3; i++) {
            commute(JOB, token, "{}").andExpect(jsonPath("$.available").value(true));
        }
        unavailable(token, "LIMITED");
        assertThat(CALLS.get()).isEqualTo(3);
        // 다른 계정은 따로 센다
        commute(JOB, guestToken(), "{}").andExpect(jsonPath("$.available").value(true));
    }

    // ───────── 오류 ─────────

    @Test
    void 사는_곳_코드가_틀리면_400_직무가_없으면_404_토큰이_없으면_401() throws Exception {
        String token = guestToken();
        commute(JOB, token, "{\"homeAreaCode\":\"41999\"}") // 형식은 맞지만 목록에 없음
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.fields[0].field").value("homeAreaCode"));
        commute(JOB, token, "{\"homeAreaCode\":\"서울\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields[0].field").value("homeAreaCode"));
        commute(999_999, token, "{}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("JOB_NOT_FOUND"));
        mvc.perform(post("/api/jobs/" + JOB + "/commute").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
        assertThat(CALLS.get()).isZero();
    }

    // ───────── 도우미 ─────────

    private void unavailable(String token, String reason) throws Exception {
        String body = commute(JOB, token, "{\"homeAreaCode\":\"11350\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.unavailableReason").value(reason))
                .andExpect(jsonPath("$.minutes").isEmpty())
                .andExpect(jsonPath("$.transfers").isEmpty())
                .andExpect(jsonPath("$.fareWon").isEmpty())
                .andReturn().getResponse().getContentAsString();
        Contract.assertSameShape(body, Contract.responseExample("getCommute", 200, "commute-unavailable.json"));
    }

    private ResultActions commute(long jobId, String token, String body) throws Exception {
        return mvc.perform(post("/api/jobs/" + jobId + "/commute")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    /** 체험 계정은 누를 때마다 새 계정이라 테스트마다 호출 한도가 따로 센다. */
    private String guestToken() throws Exception {
        String body = mvc.perform(post("/api/auth/guest")
                        .header("CF-Connecting-IP", "10.9.9." + (int) (Math.random() * 250))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"CENTER\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }

    private static HttpServer startKakao() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v2/routing/publictraffic", ex -> {
                CALLS.incrementAndGet();
                LAST_QUERY.set(ex.getRequestURI().getRawQuery());
                LAST_AUTH.set(ex.getRequestHeaders().getFirst("Authorization"));
                Reply reply = REPLY.get();
                try {
                    Thread.sleep(reply.delayMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                byte[] out = reply.body().getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/json;charset=UTF-8");
                try {
                    ex.sendResponseHeaders(reply.status(), out.length);
                    try (OutputStream os = ex.getResponseBody()) {
                        os.write(out);
                    }
                } catch (IOException e) {
                    // 제한 시간 테스트에서 클라이언트가 먼저 끊으면 여기로 온다
                } finally {
                    ex.close();
                }
            });
            server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
