package kr.ac.skuniv.coopradar.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** docs/api #3~#7. 응답은 계약 예시와 같은 모양이어야 한다(Contract.assertSameShape). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AuthApiTest {

    private static final AtomicInteger IP = new AtomicInteger(1);

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcClient db;
    @Autowired
    JwtService jwt;
    @Autowired
    GuestCleanup cleanup;

    // ───────── 가입·로그인 ─────────

    @Test
    void 가입하면_201과_토큰_내_계정까지_계약_모양() throws Exception {
        String email = "New." + UUID.randomUUID() + "@Example.com";
        MvcResult r = mvc.perform(json(post("/api/auth/signup"), creds(email, "password-8")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.user.email").value(email.toLowerCase()))
                .andExpect(jsonPath("$.user.role").value("STUDENT"))
                .andExpect(jsonPath("$.user.isGuest").value(false))
                .andExpect(jsonPath("$.user.expiresAt").isEmpty())
                .andExpect(jsonPath("$.user.hasProfile").value(false))
                .andReturn();
        String body = r.getResponse().getContentAsString();
        Contract.assertSameShape(body, Contract.responseExample("signup", 201, null));
        OffsetDateTime exp = OffsetDateTime.parse(JsonPath.read(body, "$.expiresAt"));
        assertThat(exp.getOffset().getId()).isEqualTo("+09:00");
        assertThat(exp.toInstant()).isBetween(Instant.now().plus(7, ChronoUnit.DAYS).minusSeconds(60),
                Instant.now().plus(7, ChronoUnit.DAYS).plusSeconds(60));

        String me = mvc.perform(get("/api/me").header("Authorization", "Bearer " + token(body)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Contract.assertSameShape(me, Contract.responseExample("getMe", 200, null));
    }

    @Test
    void 같은_이메일은_대소문자가_달라도_409() throws Exception {
        String email = "dup." + UUID.randomUUID() + "@example.com";
        mvc.perform(json(post("/api/auth/signup"), creds(email, "password-8"))).andExpect(status().isCreated());
        mvc.perform(json(post("/api/auth/signup"), creds(email.toUpperCase(), "password-8")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_TAKEN"));
    }

    @Test
    void 입력이_틀리면_400_INVALID_INPUT과_필드() throws Exception {
        mvc.perform(json(post("/api/auth/signup"), creds("a@example.com", "short")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.fields[0].field").value("password"));
        mvc.perform(json(post("/api/auth/signup"), creds("not-an-email", "password-8")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields[0].field").value("email"));
        mvc.perform(json(post("/api/auth/signup"), creds("a@example.com", "가".repeat(25))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields[0].field").value("password"));
        mvc.perform(json(post("/api/auth/signup"), "{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    void 로그인_성공은_200_틀리면_어느쪽인지_말하지_않는다() throws Exception {
        String email = "login." + UUID.randomUUID() + "@example.com";
        mvc.perform(json(post("/api/auth/signup"), creds(email, "password-8"))).andExpect(status().isCreated());

        String body = mvc.perform(json(post("/api/auth/login"), creds(email.toUpperCase(), "password-8")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Contract.assertSameShape(body, Contract.responseExample("login", 200, null));

        String wrongPassword = mvc.perform(json(post("/api/auth/login"), creds(email, "password-9")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("LOGIN_FAILED"))
                .andReturn().getResponse().getContentAsString();
        String noAccount = mvc.perform(json(post("/api/auth/login"), creds("none." + email, "password-8")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("LOGIN_FAILED"))
                .andReturn().getResponse().getContentAsString();
        assertThat(noAccount).isEqualTo(wrongPassword);
        // 가입 규칙(8자 이상·이메일 형식)과 다른 값도 400이 아니라 401(로그인은 형식을 따지지 않는다)
        mvc.perform(json(post("/api/auth/login"), creds(email, "short")))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("LOGIN_FAILED"));
        mvc.perform(json(post("/api/auth/login"), creds("not-an-email", "password-8")))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("LOGIN_FAILED"));
        mvc.perform(json(post("/api/auth/login"), creds(email, "가".repeat(30))))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("LOGIN_FAILED"));
    }

    // ───────── 토큰 ─────────

    @Test
    void 토큰이_없거나_틀리면_401_AUTH_REQUIRED_만료면_TOKEN_EXPIRED() throws Exception {
        mvc.perform(get("/api/me")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
        mvc.perform(get("/api/me").header("Authorization", "Basic abc")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));

        String token = token(signup());
        String tampered = token.substring(0, token.length() - 2) + (token.endsWith("A") ? "BB" : "AA");
        mvc.perform(get("/api/me").header("Authorization", "Bearer " + tampered)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
        mvc.perform(get("/api/me").header("Authorization", "Bearer eyJhbGciOiJub25lIn0.eyJzdWIiOiIxIn0."))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));

        long id = JwtTestSupport.subject(token);
        String expired = jwt.issue(id, Role.STUDENT, false, Instant.now().minusSeconds(5), 0);
        mvc.perform(get("/api/me").header("Authorization", "Bearer " + expired)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"));
    }

    // ───────── 체험 계정 ─────────

    @Test
    void 체험_STUDENT는_예시_프로필과_함께_201_사는_곳은_시드에_있을_때만() throws Exception {
        db.sql("DELETE FROM student_profile WHERE home_area_code = '11350'").update();
        db.sql("DELETE FROM area WHERE code = '11350'").update();

        String noArea = guest("STUDENT")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.isGuest").value(true))
                .andExpect(jsonPath("$.user.email").isEmpty())
                .andExpect(jsonPath("$.user.hasProfile").value(true))
                .andExpect(jsonPath("$.profile.isExample").value(true))
                .andExpect(jsonPath("$.profile.department.name").value("메이크업디자인학과"))
                .andExpect(jsonPath("$.profile.gpa").value(3.4))
                .andExpect(jsonPath("$.profile.homeAreaCode").isEmpty())
                .andExpect(jsonPath("$.profile.homeArea").isEmpty())
                // 예시 학생은 자격증 없음(ADR-0021) — null(답하지 않음)이 아니라 빈 목록
                .andExpect(jsonPath("$.profile.certificates").isArray())
                .andExpect(jsonPath("$.profile.certificates.length()").value(0))
                .andReturn().getResponse().getContentAsString();
        Contract.assertSameShape(noArea, Contract.responseExample("createGuest", 201, null));
        OffsetDateTime exp = OffsetDateTime.parse(JsonPath.read(noArea, "$.expiresAt"));
        assertThat(exp.toInstant()).isBetween(Instant.now().plus(24, ChronoUnit.HOURS).minusSeconds(60),
                Instant.now().plus(24, ChronoUnit.HOURS).plusSeconds(60));
        assertThat((String) JsonPath.read(noArea, "$.user.expiresAt")).isEqualTo(JsonPath.read(noArea, "$.expiresAt"));

        db.sql("INSERT INTO area (code, sido, name, sort_order) VALUES ('11350', '서울', '노원구', 9001)")
                .update();
        String withArea = guest("STUDENT")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.profile.homeAreaCode").value("11350"))
                .andExpect(jsonPath("$.profile.homeArea.name").value("노원구"))
                .andReturn().getResponse().getContentAsString();
        Contract.assertSameShape(withArea, Contract.responseExample("createGuest", 201, null));
    }

    @Test
    void 체험_CENTER는_프로필_없이_201() throws Exception {
        guest("CENTER")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.role").value("CENTER"))
                .andExpect(jsonPath("$.user.hasProfile").value(false))
                .andExpect(jsonPath("$.profile").isEmpty());
    }

    @Test
    void 체험_역할이_틀리면_400() throws Exception {
        mvc.perform(json(post("/api/auth/guest"), "{\"role\":\"ADMIN\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.fields[0].field").value("role"));
        mvc.perform(json(post("/api/auth/guest"), "{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields[0].field").value("role"));
    }

    @Test
    void 체험_계정은_IP당_1시간_300회_넘으면_429와_Retry_After() throws Exception {
        // 기본값 300: 시연장·학교 와이파이처럼 한 공인 IP를 여럿이 써도 막히지 않게(ADR-0013)
        String ip = "203.0.113." + (200 + IP.getAndIncrement());
        for (int i = 0; i < 300; i++) {
            mvc.perform(json(post("/api/auth/guest").header("CF-Connecting-IP", ip), "{\"role\":\"CENTER\"}"))
                    .andExpect(status().isCreated());
        }
        String retryAfter = mvc.perform(json(post("/api/auth/guest").header("CF-Connecting-IP", ip), "{\"role\":\"CENTER\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andReturn().getResponse().getHeader("Retry-After");
        assertThat(Long.parseLong(retryAfter)).isBetween(1L, 3600L);
        // 다른 IP는 영향 없음
        guest("CENTER").andExpect(status().isCreated());
    }

    @Test
    void 학과_시드가_없으면_체험_STUDENT는_프로필_없이_201() throws Exception {
        // 실제 시드(R__seed.sql)에는 예시 학과가 있다. 이름을 잠시 바꿔 '시드 전'을 만들고 끝나면 되돌린다
        db.sql("UPDATE department SET name = '메이크업디자인학과(시험 중)' WHERE name = '메이크업디자인학과'").update();
        try {
            String body = guest("STUDENT")
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.user.role").value("STUDENT"))
                    .andExpect(jsonPath("$.user.hasProfile").value(false))
                    .andExpect(jsonPath("$.profile").isEmpty())
                    .andReturn().getResponse().getContentAsString();
            // 토큰은 정상으로 쓴다
            mvc.perform(get("/api/me").header("Authorization", "Bearer " + token(body))).andExpect(status().isOk());
        } finally {
            db.sql("UPDATE department SET name = '메이크업디자인학과' WHERE name = '메이크업디자인학과(시험 중)'").update();
        }
    }

    @Test
    void 시간이_지난_체험_계정은_TOKEN_EXPIRED이고_정리하면_프로필까지_지워진다() throws Exception {
        String body = guest("STUDENT").andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String token = token(body);
        long id = ((Number) JsonPath.read(body, "$.user.id")).longValue();
        db.sql("UPDATE app_user SET expires_at = now() - interval '1 minute' WHERE id = :id").param("id", id).update();

        mvc.perform(get("/api/me").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"));

        assertThat(cleanup.run()).isGreaterThanOrEqualTo(1);
        assertThat(count("SELECT count(*) FROM app_user WHERE id = :id", id)).isZero();
        assertThat(count("SELECT count(*) FROM student_profile WHERE user_id = :id", id)).isZero();
        mvc.perform(get("/api/me").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
    }

    // ───────── 탈퇴 ─────────

    @Test
    void 탈퇴하면_204_프로필까지_지워지고_토큰은_더_못_쓴다() throws Exception {
        String body = guest("STUDENT").andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String token = token(body);
        long id = ((Number) JsonPath.read(body, "$.user.id")).longValue();
        assertThat(count("SELECT count(*) FROM student_profile WHERE user_id = :id", id)).isEqualTo(1);

        mvc.perform(delete("/api/me").header("Authorization", "Bearer " + token)).andExpect(status().isNoContent());

        assertThat(count("SELECT count(*) FROM app_user WHERE id = :id", id)).isZero();
        assertThat(count("SELECT count(*) FROM student_profile WHERE user_id = :id", id)).isZero();
        mvc.perform(get("/api/me").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
    }

    // ───────── 도우미 ─────────

    private org.springframework.test.web.servlet.ResultActions guest(String role) throws Exception {
        return mvc.perform(json(post("/api/auth/guest").header("CF-Connecting-IP", "198.51.100." + IP.getAndIncrement()),
                "{\"role\":\"" + role + "\"}"));
    }

    private String signup() throws Exception {
        return mvc.perform(json(post("/api/auth/signup"), creds("u." + UUID.randomUUID() + "@example.com", "password-8")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    }

    private long count(String sql, long id) {
        return db.sql(sql).param("id", id).query(Long.class).single();
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static String creds(String email, String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }

    private static String token(String body) {
        return JsonPath.read(body, "$.accessToken");
    }
}
