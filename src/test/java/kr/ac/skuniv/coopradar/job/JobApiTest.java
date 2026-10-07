package kr.ac.skuniv.coopradar.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** docs/api #17 직무 상세. 2026-2 실제 시드(R__seed.sql)로 본다. 응답은 계약 예시와 같은 모양이어야 한다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class JobApiTest {

    private static final AtomicInteger IP = new AtomicInteger(1);

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcClient db;

    @Test
    void 직무_101은_조건_요건_근거_수기를_계약_모양으로_준다() throws Exception {
        String body = detail(guestToken("STUDENT"), "101")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(101))
                .andExpect(jsonPath("$.round.termCode").value("2026-2"))
                .andExpect(jsonPath("$.institution.id").value(1))
                .andExpect(jsonPath("$.institution.logoPath").value("/logos/1.png"))
                .andExpect(jsonPath("$.institution.ntsStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.title").value("AE"))
                .andExpect(jsonPath("$.team").value("광고사업부"))
                .andExpect(jsonPath("$.conditions.course").value("SEMESTER"))
                .andExpect(jsonPath("$.conditions.weekdays.length()").value(5))
                .andExpect(jsonPath("$.conditions.stipend.basis").value("MONTHLY"))
                .andExpect(jsonPath("$.conditions.stipend.amount").value(1618000))
                .andExpect(jsonPath("$.conditions.stipend.minWageRatio").value(75.0)) // 1,618,000 ÷ 2,156,880
                .andExpect(jsonPath("$.conditions.headcount").value(3))
                .andExpect(jsonPath("$.requirements.gradeRule").value("Y4"))
                .andExpect(jsonPath("$.requirements.portfolio").value("REQUIRED"))
                .andExpect(jsonPath("$.requirements.majorOpen").value(false))
                .andExpect(jsonPath("$.workplace.hasCoordinates").value(true)) // 근로지 주소가 있으면 통근 조회를 부른다(ADR-0007)
                .andExpect(jsonPath("$.closing.closesOn").value("2026-07-18"))
                .andExpect(jsonPath("$.closing.closeReason").value("CENTER_CLOSED"))
                .andExpect(jsonPath("$.closing.closesOnIsVirtual").value(false))
                .andExpect(jsonPath("$.seniorNotes.length()").value(1))
                .andExpect(jsonPath("$.seniorNotes[0].teamText").value("광고사업부"))
                .andReturn().getResponse().getContentAsString();
        Contract.assertSameShape(body, Contract.responseExample("getJob", 200, null));

        // 근거: 직무 필드 먼저(부서부터), 기관 필드 뒤. 표기가 모두 붙고 DB의 근거 행 수와 같다
        List<String> keys = JsonPath.read(body, "$.evidence[*].fieldKey");
        assertThat(keys.getFirst()).isEqualTo("department");
        assertThat(keys).contains("name", "stipendAmount");
        assertThat(keys.indexOf("stipendAmount")).isLessThan(keys.indexOf("name"));
        assertThat(keys).hasSize(count("SELECT count(*) FROM field_evidence WHERE job_id = 101 OR institution_id = 1"));
        List<String> labels = JsonPath.read(body, "$.evidence[*].label");
        assertThat(labels).doesNotContainAnyElementsOf(keys);
        assertThat((String) JsonPath.read(body, "$.evidence[0].documentTitle")).isEqualTo("더에스엠씨 운영계획서");

        // 선호 전공 표기 → 확정 학과(ADR-0016): 101은 광고홍보콘텐츠학과 — 옛 이름 광고홍보영상학과도 같은 학과(ADR-0025)
        assertThat(JsonPath.<List<String>>read(body, "$.requirements.majorAliases[*].label")).containsExactly("광고홍보콘텐츠학과");
        assertThat(JsonPath.<List<String>>read(body, "$.requirements.majorAliases[0].departments[*].name"))
                .containsExactly("광고홍보영상학과", "광고홍보콘텐츠학과");

        List<Integer> weeks = JsonPath.read(body, "$.weeklyPlan[*].seq");
        assertThat(weeks).hasSize(count("SELECT count(*) FROM job_weekly_plan WHERE job_id = 101")).isSorted();

        // 수기에는 이름·학과·학년·소감이 없다. 실습 결과는 원문에서 자른 사실 구절만(ADR-0020)
        Map<String, Object> note = JsonPath.read(body, "$.seniorNotes[0]");
        assertThat(note).containsOnlyKeys("termCode", "teamText", "documentTitle", "page", "activities", "outcomes");
        assertThat((String) note.get("documentTitle")).contains("참여수기");
        assertThat(JsonPath.<List<String>>read(body, "$.seniorNotes[0].outcomes"))
                .contains("엘리베이터 광고와 홍대입구역 OOH 광고 소재를 직접 기획");
    }

    @Test
    void 직무에_걸린_알림과_기관_전체에_걸린_알림을_같이_준다() throws Exception {
        String token = guestToken("STUDENT");
        detail(token, "116")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alerts[*].id").value(contains(3, 4)))
                .andExpect(jsonPath("$.alerts[1].kind").value("LIST_MISMATCH"))
                .andExpect(jsonPath("$.alerts[1].fieldKey").value("majorRequirement"))
                .andExpect(jsonPath("$.alerts[1].jobId").value(116))
                .andExpect(jsonPath("$.alerts[1].pageA").value(4))
                .andExpect(jsonPath("$.alerts[1].pageB").isEmpty());

        // 세정(기관 2)의 문서 내부 불일치(알림 1)는 기관 전체에 걸려 있어 그 기관 직무마다 보인다
        String body = detail(token, "102").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<Integer> ids = JsonPath.read(body, "$.alerts[*].id");
        assertThat(ids).contains(1);
        List<Object> jobIds = JsonPath.read(body, "$.alerts[?(@.id == 1)].jobId");
        assertThat(jobIds).containsOnlyNulls();
        Contract.assertSameShape(body, Contract.responseExample("getJob", 200, null));
    }

    @Test
    void 운영계획서가_전공_무관이면_전공_무관이고_표기_대응은_주지_않는다() throws Exception {
        // ADR-0025: 강의제작(116) — 리스트는 '인문, 사회계열', 운영계획서는 '전공무관 3명'
        String body = detail(guestToken("STUDENT"), "116").andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
        assertThat(JsonPath.<Boolean>read(body, "$.requirements.majorOpen")).isTrue();
        assertThat(JsonPath.<List<Object>>read(body, "$.requirements.majorAliases")).isEmpty();
    }

    @Test
    void 선호_전공_표기마다_확정된_학과를_준다() throws Exception {
        String token = guestToken("STUDENT");
        String body = detail(token, "120").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(body, "$.requirements.majorAliases[*].label"))
                .containsExactly("광고홍보콘텐츠학과", "경영학부", "미용예술대학");
        List<String> beauty = JsonPath.read(body, "$.requirements.majorAliases[2].departments[*].name");
        assertThat(beauty).hasSize(8).contains("메이크업디자인학과", "헤어디자인학과");
        List<Integer> ids = JsonPath.read(body, "$.requirements.majorAliases[2].departments[*].id");
        assertThat(ids).isSorted();

        String jungeo = detail(token, "128").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(jungeo, "$.requirements.majorAliases[*].label"))
                .containsExactly("경영학부", "중어전공");
        // 중어전공: 계획서마다 가리키는 학부가 달라 둘 다 연결(직진 글로벌비즈니스어학부 · 제이숲 미래융합학부1)
        assertThat(JsonPath.<List<String>>read(jungeo, "$.requirements.majorAliases[1].departments[*].name"))
                .containsExactly("글로벌비즈니스어학부", "미래융합학부1");
    }

    @Test
    void 센터도_볼_수_있고_없는_직무는_404_토큰이_없으면_401() throws Exception {
        detail(guestToken("CENTER"), "120").andExpect(status().isOk()).andExpect(jsonPath("$.id").value(120));
        detail(guestToken("STUDENT"), "999999")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("JOB_NOT_FOUND"));
        detail(guestToken("STUDENT"), "abc")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        mvc.perform(get("/api/jobs/101"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
    }

    // ───────── 도우미 ─────────

    private ResultActions detail(String token, String jobId) throws Exception {
        return mvc.perform(get("/api/jobs/" + jobId).header("Authorization", "Bearer " + token));
    }

    private int count(String sql) {
        return db.sql(sql).query(Integer.class).single();
    }

    private String guestToken(String role) throws Exception {
        String body = mvc.perform(post("/api/auth/guest").header("CF-Connecting-IP", "203.0.113." + IP.getAndIncrement())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"" + role + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }
}
