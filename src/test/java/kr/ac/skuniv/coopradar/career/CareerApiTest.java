package kr.ac.skuniv.coopradar.career;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import kr.ac.skuniv.coopradar.TestcontainersConfiguration;
import kr.ac.skuniv.coopradar.career.CareerDrafts.CoveredDraft;
import kr.ac.skuniv.coopradar.career.CareerDrafts.UnitsDraft;
import kr.ac.skuniv.coopradar.contract.Contract;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * docs/api #33~#36 커리어(ADR-0032). AI는 가짜(StubWriter). 2026-2 시드: 소서 국내 마케팅(122) → 마케팅전략기획(11개 단위),
 * 넓혀 갈 직무 고객관리·통계조사·광고, 애브뉴준오 미용 시술 보조(140) → 헤어미용(연계표에 직업 없음).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, CareerApiTest.StubConfig.class})
class CareerApiTest {

    static final String TEXT = "1~4주차에는 회사 소개와 인스타그램 운영 방식을 배웠습니다. "
            + "5주차부터 경쟁사 신제품 30개의 가격과 구성을 표로 정리해 신제품 기획 회의에 냈고, 제안한 세트 구성 아이디어 1건이 "
            + "다음 분기 기획안에 들어갔습니다.\n매주 SNS 게시물 반응을 집계해 콘텐츠 기획 회의 자료를 만들었습니다. 연락처 010-1234-5678.";
    private static final AtomicInteger IP = new AtomicInteger(1);
    static final AtomicBoolean AVAILABLE = new AtomicBoolean(true);
    static final AtomicReference<Optional<UnitsDraft>> NEXT = new AtomicReference<>(Optional.empty());
    static final AtomicReference<String> LAST_USER = new AtomicReference<>();

    @TestConfiguration
    static class StubConfig {
        @Bean
        @Primary
        CareerWriter stubCareerWriter() {
            return new CareerWriter() {
                @Override
                public boolean available() {
                    return AVAILABLE.get();
                }

                @Override
                public String model() {
                    return "stub";
                }

                @Override
                public Optional<UnitsDraft> units(String system, String user) {
                    LAST_USER.set(user);
                    return NEXT.get();
                }
            };
        }
    }

    @Autowired
    MockMvc mvc;

    @BeforeEach
    void 초기화() {
        AVAILABLE.set(true);
        NEXT.set(Optional.empty());
    }

    @Test
    void 직무의_커리어_길은_NCS_세분류와_능력단위_넓혀_갈_직무_직업이다() throws Exception {
        String token = guestToken("STUDENT");
        String body = mvc.perform(get("/api/jobs/122/career").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ncs.code").value("02010301"))
                .andExpect(jsonPath("$.ncs.name").value("마케팅전략기획"))
                .andExpect(jsonPath("$.ncs.units.length()").value(11))
                .andReturn().getResponse().getContentAsString();
        Contract.assertSameShape(body, Contract.responseExample("getJobCareer", 200, null));
        assertThat(JsonPath.<List<String>>read(body, "$.ncs.path")).containsExactly("경영·회계·사무", "기획사무", "마케팅");
        assertThat(JsonPath.<List<String>>read(body, "$.ncs.units[*].name")).doesNotContain("마케팅전략 계획수립(구버전)")
                .contains("신상품 기획", "STP 전략 수립").doesNotContain("STP전략 수립");
        assertThat(JsonPath.<List<String>>read(body, "$.expand[*].name")).containsExactly("고객관리", "통계조사", "광고");
        assertThat(JsonPath.<List<String>>read(body, "$.expand[*].relation")).containsExactly("SAME_SMALL", "SAME_SMALL", "SAME_MIDDLE");
        assertThat(JsonPath.<List<String>>read(body, "$.occupations[*].name")).contains("상품 기획 전문가", "광고 및 홍보 전문가");
        // 헤어미용은 공식 연계표에 맞는 직업이 없다
        mvc.perform(get("/api/jobs/140/career").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.ncs.name").value("헤어미용"))
                .andExpect(jsonPath("$.occupations").isEmpty());
        // 센터 담당자도 본다(로그인), 없는 직무 404
        mvc.perform(get("/api/jobs/122/career").header("Authorization", "Bearer " + guestToken("CENTER")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/jobs/99999/career").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("JOB_NOT_FOUND"));
    }

    @Test
    void 커리어_리포트는_대조를_통과한_능력단위만_다룬_것으로_두고_나머지는_채울_것() throws Exception {
        String token = guestToken("STUDENT");
        NEXT.set(Optional.of(new UnitsDraft(List.of(
                new CoveredDraft("0201030102_21v5", "경쟁사 신제품 30개의 가격과 구성을 표로 정리해 신제품 기획 회의에 냈고",
                        "경쟁 제품을 조사해 신제품 기획 회의에 아이디어를 낸 일이 신상품 기획에 해당해요."),
                // 두 문장에 걸친 구절: 버린다
                new CoveredDraft("0201030109_21v4", "기획안에 들어갔습니다. 매주 SNS 게시물", "시장을 분석한 일이에요."),
                // 목록에 없는 단위(광고): 버린다
                new CoveredDraft("0201020205_24v4", "매주 SNS 게시물 반응을 집계해", "광고 전략을 세운 일이에요."),
                new CoveredDraft("0201030115_16v3", "매주 SNS 게시물 반응을 집계해", "SNS 반응을 매주 집계한 일이 마케팅 성과 파악이에요."),
                // 같은 단위 두 번: 앞의 것만
                new CoveredDraft("0201030102_21v5", "세트 구성 아이디어 1건", "같은 단위를 또 골랐어요.")))));
        String body = report(token, 122, TEXT, true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("AI"))
                .andExpect(jsonPath("$.ncs.unitCount").value(11))
                .andReturn().getResponse().getContentAsString();
        Contract.assertSameShape(body, Contract.responseExample("createCareerReport", 200, null));
        assertThat(JsonPath.<List<String>>read(body, "$.covered[*].code")).containsExactly("0201030102_21v5", "0201030115_16v3");
        assertThat(JsonPath.<List<String>>read(body, "$.notCovered[*].code")).hasSize(9).doesNotContain("0201030102_21v5");
        assertThat(JsonPath.<String>read(body, "$.input.practiceText")).contains("[가림]").doesNotContain("5678");
        assertThat(LAST_USER.get()).doesNotContain("5678").contains("0201030102_21v5 신상품 기획");
        // 저장본(#35), 지우기(#36)
        mvc.perform(get("/api/me/career-report").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.covered.length()").value(2))
                .andExpect(jsonPath("$.reportId").value(JsonPath.<Integer>read(body, "$.reportId")));
        mvc.perform(delete("/api/me/career-report").header("Authorization", "Bearer " + token)).andExpect(status().isNoContent());
        mvc.perform(get("/api/me/career-report").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CAREER_REPORT_NOT_FOUND"));
    }

    @Test
    void AI가_없거나_실패하거나_대조를_못_넘으면_능력단위_목록만() throws Exception {
        String token = guestToken("STUDENT");
        report(token, 122, TEXT, true).andExpect(jsonPath("$.source").value("NONE"))
                .andExpect(jsonPath("$.fallbackReason").value("AI_ERROR"))
                .andExpect(jsonPath("$.covered").isEmpty())
                .andExpect(jsonPath("$.notCovered.length()").value(11));
        NEXT.set(Optional.of(new UnitsDraft(List.of(new CoveredDraft("0201030102_21v5", "글에 없는 구절을 지어냈어요", "지어낸 근거예요.")))));
        report(token, 122, TEXT, true).andExpect(jsonPath("$.fallbackReason").value("VERIFY_FAILED"));
        AVAILABLE.set(false);
        report(token, 122, TEXT, true).andExpect(jsonPath("$.fallbackReason").value("NO_KEY"));
    }

    @Test
    void 동의_없음_짧은_글_없는_직무는_오류_센터는_403() throws Exception {
        String token = guestToken("STUDENT");
        report(token, 122, TEXT, false).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CONSENT_REQUIRED"));
        report(token, 122, "너무 짧은 실습 내용이에요.", true)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields[0].field").value("practiceText"));
        report(token, 99999, TEXT, true).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("JOB_NOT_FOUND"));
        mvc.perform(get("/api/me/career-report").header("Authorization", "Bearer " + guestToken("CENTER")))
                .andExpect(status().isForbidden());
    }

    private ResultActions report(String token, int jobId, String text, boolean consent) throws Exception {
        String json = "{\"jobId\": " + jobId + ", \"practiceText\": \"" + text.replace("\n", "\\n") + "\", \"consent\": "
                + consent + "}";
        return mvc.perform(post("/api/me/career-report").header("Authorization", "Bearer " + token)
                .header("CF-Connecting-IP", "198.51.100.9").contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private String guestToken(String role) throws Exception {
        String body = mvc.perform(post("/api/auth/guest").header("CF-Connecting-IP", "198.51.100." + (50 + IP.getAndIncrement()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"" + role + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }
}
