package kr.ac.skuniv.coopradar.reference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import java.util.List;
import kr.ac.skuniv.coopradar.TestcontainersConfiguration;
import kr.ac.skuniv.coopradar.contract.Contract;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * docs/api #2 코드값 · #11 학과 · #12 사는 곳 · #13 현재 회차. 전부 공개(토큰 없이 200).
 * 시드 테이블에는 가상 행(id·순서 9000번대, 회차·사업은 다른 테스트와 겹치지 않게 92xx)만 넣고, 실제 시드가 있어도 깨지지 않게 '포함'으로 확인한다.
 * 회차 행은 '가장 최근 회차'를 바꾸므로 테스트가 끝나면 지운다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ReferenceApiTest {

    private static final int PROGRAM = 9201;
    private static final List<Integer> ROUNDS = List.of(9201, 9202);

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcClient db;

    @AfterEach
    void 회차_정리() {
        db.sql("DELETE FROM recruit_round WHERE id IN (:ids)").param("ids", ROUNDS).update();
    }

    // ───────── #2 코드값 ─────────

    @Test
    void 코드값은_토큰_없이_200이고_계약_예시와_값까지_같다() throws Exception {
        String body = mvc.perform(get("/api/codes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verdict.NEEDS_CHECK").value("확인 필요"))
                .andReturn().getResponse().getContentAsString();
        // codes.json이 바뀌면 build_openapi.py로 계약이 다시 만들어지고, CodeLabels를 안 고쳤으면 여기서 실패한다
        assertThat(JsonMapper.builder().build().readTree(body)).isEqualTo(Contract.responseExample("getCodes", 200, null));
    }

    // ───────── #11 학과 ─────────

    @Test
    void 학과는_id_순서로_단과대학_없으면_null() throws Exception {
        department(9212, "(가상)테스트학과9212", null);
        department(9211, "(가상)테스트학과9211", "(가상)테스트대학");

        String body = mvc.perform(get("/api/departments"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Contract.assertSameShape(body, Contract.responseExample("getDepartments", 200, null));

        List<Integer> ids = JsonPath.read(body, "$.departments[*].id");
        assertThat(ids).contains(9211, 9212).isSorted();
        assertThat((List<String>) JsonPath.read(body, "$.departments[?(@.id == 9211)].college")).containsExactly("(가상)테스트대학");
        assertThat((List<Object>) JsonPath.read(body, "$.departments[?(@.id == 9212)].college")).containsExactly((Object) null);
    }

    // ───────── #12 사는 곳 ─────────

    @Test
    void 사는_곳은_시드_순서대로_좌표는_주지_않는다() throws Exception {
        area("41992", "경기", "(가상)구9102", 9102);
        area("11991", "서울", "(가상)구9101", 9101);

        String body = mvc.perform(get("/api/areas"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.areas[0].lat").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        Contract.assertSameShape(body, Contract.responseExample("getAreas", 200, null));

        List<String> codes = JsonPath.read(body, "$.areas[*].code");
        assertThat(codes).containsSubsequence("11991", "41992");
        assertThat((List<String>) JsonPath.read(body, "$.areas[?(@.code == '11991')].name")).containsExactly("(가상)구9101");
    }

    // ───────── #13 현재 회차 ─────────

    @Test
    void 현재_회차는_가장_최근_학기이고_기본_기준일은_설정값() throws Exception {
        program();
        round(9202, "2000-1", 1, "2000-07-13", "2000-07-24"); // 지난 회차
        round(9201, "2099-2", 1, "2026-07-13", "2026-07-24");

        String body = mvc.perform(get("/api/rounds/current"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(9201))
                .andExpect(jsonPath("$.programName").value("(가상)테스트 사업"))
                .andExpect(jsonPath("$.termCode").value("2099-2"))
                .andExpect(jsonPath("$.roundNo").value(1))
                .andExpect(jsonPath("$.recruitStart").value("2026-07-13"))
                .andExpect(jsonPath("$.recruitEnd").value("2026-07-24"))
                .andExpect(jsonPath("$.replay.defaultAsOf").value("2026-07-18")) // application.yml app.replay.default-as-of
                .andExpect(jsonPath("$.replay.minDate").value("2026-07-13"))
                .andExpect(jsonPath("$.replay.maxDate").value("2026-07-24"))
                .andExpect(jsonPath("$.replay.signalsAreVirtual").value(true))
                .andReturn().getResponse().getContentAsString();
        Contract.assertSameShape(body, Contract.responseExample("getCurrentRound", 200, null));
    }

    @Test
    void 같은_학기면_회차_번호가_큰_것_모집일이_없는_회차는_고르지_않는다() throws Exception {
        program();
        round(9202, "2099-2", 1, "2099-03-02", "2099-03-13");
        round(9201, "2099-2", 2, null, null); // 날짜가 없으면 리플레이 범위를 못 만든다

        mvc.perform(get("/api/rounds/current"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(9202));

        db.sql("UPDATE recruit_round SET recruit_start = DATE '2099-07-13', recruit_end = DATE '2099-07-24' WHERE id = 9201")
                .update();
        // 설정한 기준일(2026-07-18)이 모집기간보다 앞이면 시작일로 맞춘다
        mvc.perform(get("/api/rounds/current"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(9201))
                .andExpect(jsonPath("$.roundNo").value(2))
                .andExpect(jsonPath("$.replay.defaultAsOf").value("2099-07-13"));
    }

    @Test
    void 기준일은_모집기간_안으로_맞춘다() {
        LocalDate min = LocalDate.of(2026, 7, 13);
        LocalDate max = LocalDate.of(2026, 7, 24);
        assertThat(RoundService.clamp(LocalDate.of(2026, 7, 18), min, max)).isEqualTo("2026-07-18");
        assertThat(RoundService.clamp(LocalDate.of(2026, 7, 1), min, max)).isEqualTo(min);
        assertThat(RoundService.clamp(LocalDate.of(2026, 8, 1), min, max)).isEqualTo(max);
        assertThat(RoundService.clamp(null, min, max)).isEqualTo(min);
    }

    // ───────── 공통 ─────────

    @Test
    void 프론트_주소에서_오는_CORS_사전_요청을_통과한다() throws Exception {
        mvc.perform(options("/api/departments")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
        mvc.perform(options("/api/departments")
                        .header("Origin", "https://evil.example.com")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden());
    }

    // ───────── 도우미 ─────────

    private void department(int id, String name, String college) {
        db.sql("INSERT INTO department (id, name, college, enrolled_count, enrolled_as_of) "
                        + "VALUES (:id, :name, :college, 10, DATE '2025-10-01') ON CONFLICT DO NOTHING")
                .param("id", id).param("name", name).param("college", college).update();
    }

    private void area(String code, String sido, String name, int sortOrder) {
        db.sql("INSERT INTO area (code, sido, name, sort_order) "
                        + "VALUES (:code, :sido, :name, :sortOrder) ON CONFLICT DO NOTHING")
                .param("code", code).param("sido", sido).param("name", name).param("sortOrder", sortOrder).update();
    }

    private void program() {
        db.sql("INSERT INTO program (id, code, name) VALUES (:id, 'TEST_REFERENCE', '(가상)테스트 사업') ON CONFLICT DO NOTHING")
                .param("id", PROGRAM).update();
    }

    private void round(int id, String term, int roundNo, String start, String end) {
        db.sql("INSERT INTO recruit_round (id, program_id, term_code, round_no, recruit_start, recruit_end) "
                        + "VALUES (:id, :program, :term, :roundNo, CAST(:start AS date), CAST(:end AS date)) ON CONFLICT DO NOTHING")
                .param("id", id).param("program", PROGRAM).param("term", term).param("roundNo", roundNo)
                .param("start", start).param("end", end).update();
    }
}
