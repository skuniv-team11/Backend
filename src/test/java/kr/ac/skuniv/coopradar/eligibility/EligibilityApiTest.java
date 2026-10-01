package kr.ac.skuniv.coopradar.eligibility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * docs/api #14 판정. 2026-2 실제 시드(R__seed.sql)로 본다 — 40직무, 예시 학생(메이크업디자인학과 id 43, 3학년, 5학기, 3.4).
 * 기대 숫자는 seed.json으로 같은 규칙을 따로 계산해 맞춘 값이다(ADR-0016의 23·17·0과 같다).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class EligibilityApiTest {

    private static final AtomicInteger IP = new AtomicInteger(1);
    private static final int MAKEUP = 43;

    @Autowired
    MockMvc mvc;

    @Test
    void 예시_학생은_40직무_중_23개_지원_가능_17개_확인_필요이고_계약_모양과_같다() throws Exception {
        String body = check(guestToken("STUDENT"), profile(MAKEUP, 3, 5, "3.4", false))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.round.termCode").value("2026-2"))
                .andExpect(jsonPath("$.summary.total").value(40))
                .andExpect(jsonPath("$.summary.eligible").value(23))
                .andExpect(jsonPath("$.summary.needsCheck").value(17))
                .andExpect(jsonPath("$.summary.ineligible").value(0))
                .andExpect(jsonPath("$.jobs.length()").value(40))
                .andReturn().getResponse().getContentAsString();
        Contract.assertSameShape(body, Contract.responseExample("checkEligibility", 200, null));

        // 목록 순서: 지원 가능 → 확인 필요 → 지원 불가
        List<String> verdicts = JsonPath.read(body, "$.jobs[*].verdict");
        assertThat(verdicts.subList(0, 23)).containsOnly("ELIGIBLE");
        assertThat(verdicts.subList(23, 40)).containsOnly("NEEDS_CHECK");

        // 선호 전공이 메이크업디자인학과로 확정된 직무 4개(소서 AMD·국내/해외 마케팅, 비욘드)
        List<Integer> matched = JsonPath.read(body, "$.jobs[?(@.majorMatch == 'MATCH')].jobId");
        assertThat(matched).containsExactlyInAnyOrder(120, 122, 123, 134);

        // 101: 4학년 요건 + 포트폴리오 필수 → 확인 필요
        Map<String, Object> job101 = job(body, 101);
        assertThat(job101.get("verdict")).isEqualTo("NEEDS_CHECK");
        assertThat(reason(job101, "학년")).containsEntry("requirement", "4학년").containsEntry("mine", "3학년")
                .containsEntry("result", "NOT_MET");
        assertThat(reason(job101, "포트폴리오")).containsEntry("result", "CHECK");

        // 117: 졸업예정자 요건 + 학점 3.5 이상
        Map<String, Object> job117 = job(body, 117);
        assertThat(reason(job117, "학년")).containsEntry("requirement", "졸업예정자").containsEntry("result", "NOT_MET");
        assertThat(reason(job117, "학점")).containsEntry("requirement", "3.5 이상").containsEntry("mine", "3.4");

        // 116: 선호 전공·기간 불일치 알림(3·4)은 판정을 바꾸지 않는다(ADR-0016) → 지원 가능, alertCount 2
        Map<String, Object> job116 = job(body, 116);
        assertThat(job116.get("verdict")).isEqualTo("ELIGIBLE");
        assertThat(job116.get("alertCount")).isEqualTo(2);
        // 시드의 알림은 판정 항목(학년·학점·포트폴리오·자격증)에 걸린 게 없어 alertId 줄이 없다
        List<Integer> alertIds = JsonPath.read(body, "$.jobs[*].reasons[*].alertId");
        assertThat(alertIds).isEmpty();
        assertThat(body).doesNotContain("\"alertId\":null");
        // alertCount = 직무 알림 수(기관 단위 제외): 115·130·131·138 각 1, 116 2, 나머지 0
        List<Integer> counts = JsonPath.read(body, "$.jobs[*].alertCount");
        assertThat(counts.stream().mapToInt(Integer::intValue).sum()).isEqualTo(6);
        assertThat(job(body, 102).get("alertCount")).isEqualTo(0); // 세정 기관 단위 알림은 세지 않는다

        // closing: 직무 상세와 같은 값(101은 7/18 센터 모집마감)
        assertThat(JsonPath.<List<String>>read(body, "$.jobs[?(@.jobId == 101)].closing.closesOn"))
                .containsExactly("2026-07-18");
        assertThat(JsonPath.<List<String>>read(body, "$.jobs[?(@.jobId == 101)].closing.closeReason"))
                .containsExactly("CENTER_CLOSED");
    }

    @Test
    void 이수_학기가_모자라면_40개_모두_지원_불가() throws Exception {
        String body = check(memberToken(), profile(MAKEUP, 2, 3, "4.5", false))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.ineligible").value(40))
                .andReturn().getResponse().getContentAsString();
        List<String> results = JsonPath.read(body, "$.jobs[*].reasons[?(@.item == '이수 학기')].result");
        assertThat(results).hasSize(40).containsOnly("NOT_MET");
    }

    @Test
    void 졸업예정_4학년_평점_4점5면_37개_지원_가능() throws Exception {
        check(memberToken(), profile(MAKEUP, 4, 7, "4.5", true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.eligible").value(37))
                .andExpect(jsonPath("$.summary.needsCheck").value(3));
    }

    @Test
    void 형식이_틀리거나_없는_학과면_400_INVALID_INPUT과_profile_필드() throws Exception {
        String token = memberToken();
        String body = check(token, """
                {"profile": {"departmentId": 43, "grade": 5, "completedSemesters": 9, "gpa": 3.45,
                 "graduationExpected": false, "interestText": null, "homeAreaCode": "99999"}}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andReturn().getResponse().getContentAsString();
        List<String> fields = JsonPath.read(body, "$.fields[*].field");
        assertThat(fields).contains("profile.grade", "profile.completedSemesters", "profile.gpa", "profile.homeAreaCode");
        assertThat(body).doesNotContain("3.45").doesNotContain("99999");

        check(token, profile(9999, 3, 5, "3.4", false))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields[0].field").value("profile.departmentId"));
        check(token, "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields[0].field").value("profile"));
        check(token, "{\"profile\": {\"departmentId\": 43}}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    void 센터는_403_토큰이_없으면_401() throws Exception {
        check(guestToken("CENTER"), profile(MAKEUP, 3, 5, "3.4", false))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_ROLE"));
        mvc.perform(post("/api/eligibility").contentType(MediaType.APPLICATION_JSON)
                        .content(profile(MAKEUP, 3, 5, "3.4", false)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
    }

    // ───────── 도우미 ─────────

    private ResultActions check(String token, String body) throws Exception {
        return mvc.perform(post("/api/eligibility").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static String profile(int departmentId, int grade, int semesters, String gpa, boolean graduating) {
        return """
                {"profile": {"departmentId": %d, "grade": %d, "completedSemesters": %d, "gpa": %s,
                 "graduationExpected": %s, "interestText": "뷰티 브랜드 SNS 마케팅", "homeAreaCode": "11350"}}"""
                .formatted(departmentId, grade, semesters, gpa, graduating);
    }

    private static Map<String, Object> job(String body, int jobId) {
        List<Map<String, Object>> jobs = JsonPath.read(body, "$.jobs[?(@.jobId == " + jobId + ")]");
        assertThat(jobs).hasSize(1);
        return jobs.getFirst();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> reason(Map<String, Object> job, String item) {
        return ((List<Map<String, Object>>) job.get("reasons")).stream()
                .filter(r -> item.equals(r.get("item"))).findFirst()
                .orElseThrow(() -> new AssertionError("이유 줄 없음: " + item));
    }

    private String memberToken() throws Exception {
        String body = mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"e." + UUID.randomUUID() + "@example.com\",\"password\":\"password-8\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }

    private String guestToken(String role) throws Exception {
        String body = mvc.perform(post("/api/auth/guest").header("CF-Connecting-IP", "198.51.100." + IP.getAndIncrement())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"" + role + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }
}
