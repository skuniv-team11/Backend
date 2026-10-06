package kr.ac.skuniv.coopradar.me;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * docs/api #8 저장한 프로필 · #9 프로필 저장(동의 필수) · #10 프로필만 삭제. 응답은 계약 예시와 같은 모양이어야 한다.
 * 시드 테이블에는 가상 행(id·순서 9000번대)만 넣는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ProfileApiTest {

    private static final AtomicInteger IP = new AtomicInteger(1);
    private static final int DEPT_A = 9011;
    private static final int DEPT_B = 9012;
    private static final String AREA = "28991";

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcClient db;

    @BeforeEach
    void 시드() {
        department(DEPT_A, "(가상)프로필학과9011");
        department(DEPT_B, "(가상)프로필학과9012");
        db.sql("INSERT INTO area (code, sido, name, sort_order) "
                + "VALUES ('" + AREA + "', '인천', '(가상)구9111', 9111) ON CONFLICT DO NOTHING").update();
        db.sql("INSERT INTO certificate (code, label, sort_order) VALUES ('TEST_PROFILE_A', '(가상)자격증A', 9001), "
                + "('TEST_PROFILE_B', '(가상)자격증B', 9002) ON CONFLICT DO NOTHING").update();
    }

    // ───────── #9 저장 → #8 조회 ─────────

    @Test
    void 동의하고_저장하면_200_다시_조회해도_같고_내_계정에_hasProfile() throws Exception {
        String token = memberToken();
        mvc.perform(get("/api/me/profile").header("Authorization", bearer(token)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROFILE_NOT_FOUND"));

        String saved = save(token, profile(DEPT_A, AREA, true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.departmentId").value(DEPT_A))
                .andExpect(jsonPath("$.department.name").value("(가상)프로필학과9011"))
                .andExpect(jsonPath("$.grade").value(3))
                .andExpect(jsonPath("$.completedSemesters").value(5))
                .andExpect(jsonPath("$.gpa").value(3.4))
                .andExpect(jsonPath("$.graduationExpected").value(false))
                .andExpect(jsonPath("$.interestText").value("뷰티 브랜드 SNS 마케팅"))
                .andExpect(jsonPath("$.homeAreaCode").value(AREA))
                .andExpect(jsonPath("$.homeArea.name").value("(가상)구9111"))
                .andExpect(jsonPath("$.isExample").value(false))
                .andExpect(jsonPath("$.consent").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        Contract.assertSameShape(saved, Contract.responseExample("saveMyProfile", 200, null));
        assertThat(OffsetDateTime.parse(JsonPath.read(saved, "$.consentedAt")).getOffset().getId()).isEqualTo("+09:00");

        String read = mvc.perform(get("/api/me/profile").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Contract.assertSameShape(read, Contract.responseExample("getMyProfile", 200, null));
        assertThat(read).isEqualTo(saved);

        mvc.perform(get("/api/me").header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.hasProfile").value(true));
    }

    @Test
    void 다시_저장하면_덮어쓰고_사는_곳을_비우면_null() throws Exception {
        String token = memberToken();
        save(token, profile(DEPT_A, AREA, true)).andExpect(status().isOk());

        String body = """
                {"departmentId": %d, "grade": 4, "completedSemesters": 7, "gpa": 4.5, "graduationExpected": true,
                 "interestText": null, "homeAreaCode": null, "consent": true}""".formatted(DEPT_B);
        save(token, body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.departmentId").value(DEPT_B))
                .andExpect(jsonPath("$.grade").value(4))
                .andExpect(jsonPath("$.gpa").value(4.5))
                .andExpect(jsonPath("$.graduationExpected").value(true))
                .andExpect(jsonPath("$.interestText").isEmpty())
                .andExpect(jsonPath("$.homeAreaCode").isEmpty())
                .andExpect(jsonPath("$.homeArea").isEmpty());
        assertThat(count("SELECT count(*) FROM student_profile WHERE user_id = :id", userId(token))).isEqualTo(1);
    }

    @Test
    void 자격증은_코드표_순서로_중복_없이_저장하고_빈_목록과_null을_구분한다() throws Exception {
        // ADR-0021: null = 답하지 않음, [] = 없음. 판정의 자격증 줄이 둘을 다르게 본다
        String token = memberToken();
        String saved = save(token, profile(DEPT_A, AREA, true, "[\"TEST_PROFILE_B\", \"TEST_PROFILE_A\", \"TEST_PROFILE_B\"]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.certificates.length()").value(2))
                .andExpect(jsonPath("$.certificates[0]").value("TEST_PROFILE_A"))
                .andExpect(jsonPath("$.certificates[1]").value("TEST_PROFILE_B"))
                .andReturn().getResponse().getContentAsString();
        Contract.assertSameShape(saved, Contract.responseExample("saveMyProfile", 200, null));
        mvc.perform(get("/api/me/profile").header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.certificates[1]").value("TEST_PROFILE_B"));

        save(token, profile(DEPT_A, AREA, true, "[]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.certificates").isArray())
                .andExpect(jsonPath("$.certificates.length()").value(0));
        save(token, profile(DEPT_A, AREA, true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.certificates").value(org.hamcrest.Matchers.nullValue()));
        assertThat(db.sql("SELECT certificates IS NULL FROM student_profile WHERE user_id = :id")
                .param("id", userId(token)).query(Boolean.class).single()).isTrue();

        for (String bad : List.of("[\"NOPE\"]", "[null]")) {
            save(token, profile(DEPT_A, AREA, true, bad))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                    .andExpect(jsonPath("$.fields[0].field").value("certificates"));
        }
    }

    @Test
    void 동의가_없거나_false면_400_CONSENT_REQUIRED이고_저장하지_않는다() throws Exception {
        String token = memberToken();
        save(token, profile(DEPT_A, AREA, false))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CONSENT_REQUIRED"));
        save(token, profile(DEPT_A, AREA, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CONSENT_REQUIRED"));
        assertThat(count("SELECT count(*) FROM student_profile WHERE user_id = :id", userId(token))).isZero();
    }

    @Test
    void 형식이_틀리거나_시드에_없는_값은_400_INVALID_INPUT과_필드() throws Exception {
        String token = memberToken();
        String bad = """
                {"departmentId": %d, "grade": 5, "completedSemesters": 9, "gpa": 3.45, "graduationExpected": false,
                 "interestText": "%s", "homeAreaCode": "99999", "consent": true}""".formatted(DEPT_A, "가".repeat(201));
        String body = save(token, bad)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andReturn().getResponse().getContentAsString();
        List<String> fields = JsonPath.read(body, "$.fields[*].field");
        assertThat(fields).contains("grade", "completedSemesters", "gpa", "interestText", "homeAreaCode");
        assertThat(body).doesNotContain("3.45").doesNotContain("99999"); // 입력값은 돌려주지 않는다

        save(token, profile(9999, AREA, true))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields[0].field").value("departmentId"));
        save(token, profile(DEPT_A, "41999", true))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields[0].field").value("homeAreaCode"));
        save(token, "{\"consent\": true}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        save(token, "{")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        assertThat(count("SELECT count(*) FROM student_profile WHERE user_id = :id", userId(token))).isZero();
    }

    // ───────── #10 삭제 ─────────

    @Test
    void 프로필만_지우면_204_계정은_남고_다시_지워도_204() throws Exception {
        String token = memberToken();
        save(token, profile(DEPT_A, AREA, true)).andExpect(status().isOk());

        mvc.perform(delete("/api/me/profile").header("Authorization", bearer(token))).andExpect(status().isNoContent());

        mvc.perform(get("/api/me/profile").header("Authorization", bearer(token)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROFILE_NOT_FOUND"));
        mvc.perform(get("/api/me").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasProfile").value(false));
        mvc.perform(delete("/api/me/profile").header("Authorization", bearer(token))).andExpect(status().isNoContent());
    }

    // ───────── 체험 계정·권한 ─────────

    @Test
    void 체험_STUDENT의_예시_프로필은_isExample_true() throws Exception {
        String token = guestToken("STUDENT");
        String body = mvc.perform(get("/api/me/profile").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isExample").value(true))
                .andExpect(jsonPath("$.department.name").value("메이크업디자인학과"))
                .andExpect(jsonPath("$.certificates.length()").value(0))
                .andReturn().getResponse().getContentAsString();
        Contract.assertSameShape(body, Contract.responseExample("getMyProfile", 200, null));

        // 체험 계정도 저장할 수 있다(24시간 뒤 계정째 지워짐)
        save(token, profile(DEPT_A, AREA, true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.departmentId").value(DEPT_A))
                .andExpect(jsonPath("$.isExample").value(true));
    }

    @Test
    void 센터는_403_FORBIDDEN_ROLE_토큰이_없으면_401() throws Exception {
        String center = guestToken("CENTER");
        mvc.perform(get("/api/me/profile").header("Authorization", bearer(center)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_ROLE"));
        save(center, profile(DEPT_A, AREA, true))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_ROLE"));
        mvc.perform(delete("/api/me/profile").header("Authorization", bearer(center)))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/me/profile")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
        mvc.perform(json(put("/api/me/profile"), profile(DEPT_A, AREA, true))).andExpect(status().isUnauthorized());
    }

    // ───────── 도우미 ─────────

    private ResultActions save(String token, String body) throws Exception {
        return mvc.perform(json(put("/api/me/profile").header("Authorization", bearer(token)), body));
    }

    private static String profile(int departmentId, String area, Boolean consent) {
        return """
                {"departmentId": %d, "grade": 3, "completedSemesters": 5, "gpa": 3.4, "graduationExpected": false,
                 "interestText": "뷰티 브랜드 SNS 마케팅", "homeAreaCode": %s, "consent": %s}"""
                .formatted(departmentId, area == null ? "null" : "\"" + area + "\"", consent);
    }

    /** certificates: JSON 배열 글(예: "[]"). */
    private static String profile(int departmentId, String area, Boolean consent, String certificates) {
        return """
                {"departmentId": %d, "grade": 3, "completedSemesters": 5, "gpa": 3.4, "graduationExpected": false,
                 "interestText": "뷰티 브랜드 SNS 마케팅", "homeAreaCode": %s, "certificates": %s, "consent": %s}"""
                .formatted(departmentId, area == null ? "null" : "\"" + area + "\"", certificates, consent);
    }

    private String memberToken() throws Exception {
        String body = mvc.perform(json(post("/api/auth/signup"),
                        "{\"email\":\"p." + UUID.randomUUID() + "@example.com\",\"password\":\"password-8\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }

    private String guestToken(String role) throws Exception {
        String body = mvc.perform(json(post("/api/auth/guest").header("CF-Connecting-IP", "192.0.2." + IP.getAndIncrement()),
                        "{\"role\":\"" + role + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }

    private long userId(String token) throws Exception {
        String me = mvc.perform(get("/api/me").header("Authorization", bearer(token)))
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(me, "$.id")).longValue();
    }

    private void department(int id, String name) {
        db.sql("INSERT INTO department (id, name, college, enrolled_count, enrolled_as_of) "
                        + "VALUES (:id, :name, NULL, 10, DATE '2025-10-01') ON CONFLICT DO NOTHING")
                .param("id", id).param("name", name).update();
    }

    private long count(String sql, long id) {
        return db.sql(sql).param("id", id).query(Long.class).single();
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
