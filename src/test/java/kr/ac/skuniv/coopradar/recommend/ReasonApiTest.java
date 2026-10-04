package kr.ac.skuniv.coopradar.recommend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import kr.ac.skuniv.coopradar.TestcontainersConfiguration;
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
 * docs/api #16 추천 이유 문장. LLM은 가짜(StubWriter)로 바꿔 끼운다 — 실제 Claude를 부르지 않는다.
 * 계정당 한도는 3회로 낮춰 둔다. 2026-2 실제 시드의 소서 국내 마케팅(122)으로 본다.
 */
@SpringBootTest(properties = "app.reason.per-user-per-hour=3")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ReasonApiTest.StubConfig.class})
class ReasonApiTest {

    private static final AtomicInteger IP = new AtomicInteger(1);
    static final AtomicReference<Optional<ReasonDraft>> NEXT = new AtomicReference<>(Optional.empty());
    static final AtomicInteger CALLS = new AtomicInteger();
    static final AtomicReference<String> LAST_MESSAGE = new AtomicReference<>();

    @TestConfiguration
    static class StubConfig {
        @Bean
        @Primary
        ReasonWriter stubWriter() {
            return (system, user) -> {
                CALLS.incrementAndGet();
                LAST_MESSAGE.set(user);
                return NEXT.get();
            };
        }
    }

    @Autowired
    MockMvc mvc;

    @BeforeEach
    void 초기화() {
        CALLS.set(0);
        NEXT.set(Optional.empty());
    }

    @Test
    void LLM_문장이_검증을_통과하면_LLM_같은_요청은_CACHE이고_계약_모양과_같다() throws Exception {
        String token = guestToken("STUDENT");
        NEXT.set(Optional.of(new ReasonDraft(
                "뷰티 브랜드 SNS 마케팅에 관심이 있다면, 선배가 맡았던 'SNS 계정 관리 및 업로드'와 바로 이어지는 자리예요.",
                List.of(1L))));
        String body = reason(token, 122, "\"뷰티 브랜드 SNS 마케팅\"")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value(122))
                .andExpect(jsonPath("$.source").value("LLM"))
                .andExpect(jsonPath("$.citations[0].sourceType").value("TESTIMONIAL"))
                .andReturn().getResponse().getContentAsString();
        Contract.assertSameShape(body, Contract.responseExample("getRecommendationReason", 200, null));
        assertThat(JsonPath.<String>read(body, "$.text")).startsWith("뷰티 브랜드 SNS 마케팅에 관심이 있다면")
                // 추천 안 같은 팀 직무(123 해외 마케팅)와의 차이는 규칙 문장으로 덧붙인다(ADR-0020)
                .endsWith("같은 팀의 해외 마케팅과 달리 '영어 가능자' 요건은 없어요.");
        // 근거 인용은 추천(#15)과 같다
        assertThat(JsonPath.<String>read(body, "$.citations[0].quote")).isEqualTo("SNS 계정 관리 및 업로드");
        // LLM에는 기관 업종과 주차 계획도 사실로 준다
        assertThat(LAST_MESSAGE.get()).contains("기관 업종: 도소매 제조업 / 화장품").contains("주차 계획: ");

        // LLM에는 평점을 보내지 않는다
        assertThat(LAST_MESSAGE.get()).contains("메이크업디자인학과").contains("3학년").doesNotContain("3.4");

        reason(token, 122, "\"뷰티 브랜드 SNS 마케팅\"")
                .andExpect(jsonPath("$.source").value("CACHE"));
        assertThat(CALLS.get()).isEqualTo(1);
    }

    @Test
    void LLM이_실패하거나_근거에_없는_인용을_쓰면_200_TEMPLATE() throws Exception {
        String token = guestToken("STUDENT");
        reason(token, 122, "\"마케팅 실무\"")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("TEMPLATE"))
                .andExpect(jsonPath("$.text").isNotEmpty());

        NEXT.set(Optional.of(new ReasonDraft("이 회사는 '업계 1위 브랜드'라서 꼭 지원해 보세요.", List.of())));
        reason(token, 122, "\"브랜드 운영\"").andExpect(jsonPath("$.source").value("TEMPLATE"));

        NEXT.set(Optional.of(new ReasonDraft("근거 번호가 맞지 않는 문장이에요.", List.of(9L))));
        reason(token, 122, "\"상품 기획\"").andExpect(jsonPath("$.source").value("TEMPLATE"));
        assertThat(CALLS.get()).isEqualTo(3);
    }

    @Test
    void 선호_전공_밖이면_안내를_붙이고_관심_낱말을_원문에_없는_업종에_붙이면_TEMPLATE() throws Exception {
        String token = guestToken("STUDENT");
        // 세정 SNS·영상(102): 의류 회사, 예시 학생 학과는 선호 전공 밖
        NEXT.set(Optional.of(new ReasonDraft("'SNS 매거진 채널' 콘텐츠 기획을 거들어요.", List.of(1L))));
        reason(token, 102, "\"뷰티 브랜드 SNS 마케팅\"")
                .andExpect(jsonPath("$.source").value("LLM"))
                .andExpect(jsonPath("$.text").value("'SNS 매거진 채널' 콘텐츠 기획을 거들어요. " + ReasonTemplates.MAJOR_NOT_LISTED));
        NEXT.set(Optional.of(new ReasonDraft("SNS 매거진 채널에서 뷰티 브랜드의 마케팅 전략을 배워요.", List.of())));
        reason(token, 102, "\"뷰티 브랜드 SNS 콘텐츠\"").andExpect(jsonPath("$.source").value("TEMPLATE"));
        NEXT.set(Optional.of(new ReasonDraft("메이크업 디자인 지식을 바탕으로 콘텐츠 제작을 익혀요.", List.of())));
        reason(token, 102, "\"뷰티 SNS 콘텐츠\"").andExpect(jsonPath("$.source").value("TEMPLATE"));
        assertThat(CALLS.get()).isEqualTo(3);
    }

    @Test
    void 계정당_한도를_넘으면_LLM을_부르지_않고_TEMPLATE() throws Exception {
        String token = guestToken("STUDENT");
        NEXT.set(Optional.of(new ReasonDraft("관심 분야와 직무 개요가 이어지는 자리예요.", List.of())));
        for (String interest : new String[] {"\"가\"", "\"나\"", "\"다\""}) {
            reason(token, 122, interest).andExpect(jsonPath("$.source").value("LLM"));
        }
        reason(token, 122, "\"라\"").andExpect(jsonPath("$.source").value("TEMPLATE"));
        assertThat(CALLS.get()).isEqualTo(3);
    }

    @Test
    void 지원_불가_직무는_LLM_없이_TEMPLATE_없는_직무는_404_센터는_403() throws Exception {
        String token = guestToken("STUDENT");
        mvc.perform(post("/api/recommendations/122/reason").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"profile\": {\"departmentId\": 43, \"grade\": 3, \"completedSemesters\": 3, \"gpa\": 3.4,"
                                + " \"graduationExpected\": false}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("TEMPLATE"))
                .andExpect(jsonPath("$.text").value(ReasonService.INELIGIBLE_TEXT));
        reason(token, 999999, "null")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("JOB_NOT_FOUND"));
        reason(guestToken("CENTER"), 122, "null")
                .andExpect(status().isForbidden());
        assertThat(CALLS.get()).isZero();
    }

    private ResultActions reason(String token, long jobId, String interest) throws Exception {
        return mvc.perform(post("/api/recommendations/" + jobId + "/reason").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"profile": {"departmentId": 43, "grade": 3, "completedSemesters": 5, "gpa": 3.4,
                         "graduationExpected": false, "interestText": %s, "homeAreaCode": null}}""".formatted(interest)));
    }

    private String guestToken(String role) throws Exception {
        String body = mvc.perform(post("/api/auth/guest").header("CF-Connecting-IP", "198.51.100." + (200 + IP.getAndIncrement()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"" + role + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }
}
