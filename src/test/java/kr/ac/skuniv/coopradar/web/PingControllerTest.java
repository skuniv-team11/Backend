package kr.ac.skuniv.coopradar.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import kr.ac.skuniv.coopradar.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "app.cors.allowed-origin-patterns=https://coop-radar.vercel.app,https://coop-radar-*.vercel.app")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PingControllerTest {

    @Autowired
    MockMvc mvc;

    @Test
    void ping() throws Exception {
        mvc.perform(get("/api/ping"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                // 한국 시간, 초 단위(계약 예시 2026-09-30T14:00:00+09:00)
                .andExpect(jsonPath("$.time").value(org.hamcrest.Matchers.matchesPattern(
                        "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\+09:00$")));
    }

    @Test
    void 다른_출처_JS가_Retry_After와_파일_이름을_읽을_수_있다() throws Exception {
        mvc.perform(get("/api/ping").header("Origin", "https://coop-radar.vercel.app"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Expose-Headers",
                        org.hamcrest.Matchers.allOf(org.hamcrest.Matchers.containsString("Retry-After"),
                                org.hamcrest.Matchers.containsString("Content-Disposition"))));
    }

    @Test
    void 허용된_프론트_주소는_CORS_통과() throws Exception {
        mvc.perform(options("/api/ping")
                        .header("Origin", "https://coop-radar-git-main.vercel.app")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://coop-radar-git-main.vercel.app"));
    }

    @Test
    void 다른_주소는_CORS_거부() throws Exception {
        mvc.perform(options("/api/ping")
                        .header("Origin", "https://evil.example.com")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden());
    }

    @Test
    void 로그인이_필요한_API도_CORS_사전요청은_토큰_없이_통과하고_DELETE를_허용() throws Exception {
        mvc.perform(options("/api/me")
                        .header("Origin", "https://coop-radar.vercel.app")
                        .header("Access-Control-Request-Method", "DELETE")
                        .header("Access-Control-Request-Headers", "Authorization"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://coop-radar.vercel.app"))
                .andExpect(header().string("Access-Control-Allow-Methods", org.hamcrest.Matchers.containsString("DELETE")));
    }

    @Test
    void 헬스체크() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
