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
 * docs/api #14 판정. 2026-2 실제 시드(R__seed.sql)로 본다 — 40직무, 예시 학생(메이크업디자인학과 id 43, 3학년, 5학기, 3.4,
 * 자격증 없음). 기대 숫자는 seed.json으로 같은 규칙을 따로 계산해 맞춘 값이다(ADR-0021의 22·17·1 — 자격증을 묻기 전 ADR-0016은 23·17·0).
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
    void 예시_학생은_40직무_중_22개_지원_가능_1개_확인_필요_17개_지원_불가이고_계약_모양과_같다() throws Exception {
        // ADR-0024: 학년·학점·필수 자격증을 못 맞추면 지원 불가, 확인 필요는 준비할 서류(포트폴리오)뿐
        String body = check(guestToken("STUDENT"), profile(MAKEUP, 3, 5, "3.4", false, "[]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.round.termCode").value("2026-2"))
                .andExpect(jsonPath("$.summary.total").value(40))
                .andExpect(jsonPath("$.summary.eligible").value(22))
                .andExpect(jsonPath("$.summary.needsCheck").value(1))
                .andExpect(jsonPath("$.summary.ineligible").value(17))
                .andExpect(jsonPath("$.jobs.length()").value(40))
                .andReturn().getResponse().getContentAsString();
        Contract.assertSameShape(body, Contract.responseExample("checkEligibility", 200, null));

        // 목록 순서: 지원 가능 → 확인 필요 → 지원 불가
        List<String> verdicts = JsonPath.read(body, "$.jobs[*].verdict");
        assertThat(verdicts.subList(0, 22)).containsOnly("ELIGIBLE");
        assertThat(verdicts.get(22)).isEqualTo("NEEDS_CHECK");
        assertThat(verdicts.subList(23, 40)).containsOnly("INELIGIBLE");
        // 같은 판정 안에서는 적합도 순(ADR-0027) — 맨 위 5개가 추천 카드와 같은 순서다(RecommendApiTest)
        assertThat(JsonPath.<List<Integer>>read(body, "$.jobs[0:5].jobId")).containsExactly(122, 123, 120, 102, 103);
        // 확인 필요 1개는 115 영상 콘텐츠 디자인 — 3·4학년이라 학년은 맞고 포트폴리오만 준비하면 된다
        assertThat(reason(job(body, 115), "포트폴리오")).containsEntry("mine", "직접 준비").containsEntry("result", "CHECK");

        // 140 미용 시술 보조: 미용 자격증 필수인데 없음 → 지원 불가, 운영계획서 근거가 붙는다(ADR-0021)
        Map<String, Object> job140 = job(body, 140);
        assertThat(job140.get("verdict")).isEqualTo("INELIGIBLE");
        assertThat(reason(job140, "자격증")).containsEntry("layer", "INSTITUTION")
                .containsEntry("requirement", "필수 (미용 자격증 or 미용 면허증 소지자)")
                .containsEntry("mine", "없음").containsEntry("result", "NOT_MET")
                .containsEntry("citation", Map.of("sourceType", "OPERATION_PLAN",
                        "documentTitle", "애브뉴준오 더현대서울점 운영계획서", "page", 2,
                        "quote", "미용 자격증 or 미용 면허증 소지자"));
        // 117·118 로젠: 회계 자격증 우대 → 참고 줄만(판정을 바꾸지 않는다)
        assertThat(reason(job(body, 118), "자격증")).containsEntry("requirement", "우대 (회계관련 자격증 취득 및 지식(전공자) 함양)")
                .containsEntry("mine", "없음").containsEntry("result", "INFO");
        // 판정 이유 줄마다 출처(ADR-0023): 학교 규정 = 모집안내 문장, 학년·학점·포트폴리오·선호 전공 = 참여기관 리스트 칸
        String list = "2026학년도 2학기 표준 현장실습학기제 참여기관 리스트";
        Map<String, Object> cited = job(body, 101);
        assertThat(reason(cited, "이수 학기")).containsEntry("citation", cite("SCHOOL_NOTICE",
                "2026학년도 2학기 표준 현장실습학기제 학생 모집안내", null,
                "4학기 이상 수료한 재학생(편입생의 경우 본교에서 1학기 이상 이수한 학생)"));
        assertThat(reason(cited, "학년")).containsEntry("citation", cite("INSTITUTION_LIST", list, null, "학년: 4학년"));
        assertThat(reason(cited, "포트폴리오"))
                .containsEntry("citation", cite("INSTITUTION_LIST", list, null, "포트폴리오 필수 제출"));
        assertThat(reason(cited, "선호 전공"))
                .containsEntry("citation", cite("INSTITUTION_LIST", list, null, "전공: 광고홍보콘텐츠학과"));
        assertThat(reason(job(body, 117), "학점"))
                .containsEntry("citation", cite("INSTITUTION_LIST", list, null, "학점 3.5 이상"));
        assertThat(reason(job(body, 117), "자격증")).containsEntry("citation",
                cite("OPERATION_PLAN", "로젠 운영계획서", 2, "회계관련 자격증 취득 및 지식(전공자) 함양"));
        // 2026-2 시드는 알림으로 새로 만든 행이 없어 모든 행에 출처가 있다. 쪽이 없으면 page는 null로 나간다
        List<Object> withCitation = JsonPath.read(body, "$.jobs[*].reasons[?(@.citation)]");
        List<Object> all = JsonPath.read(body, "$.jobs[*].reasons[*]");
        assertThat(withCitation).hasSameSizeAs(all);
        assertThat(body).contains("\"page\":null");

        // 선호 전공이 메이크업디자인학과로 확정된 직무 4개(소서 AMD·국내/해외 마케팅, 비욘드)
        List<Integer> matched = JsonPath.read(body, "$.jobs[?(@.majorMatch == 'MATCH')].jobId");
        assertThat(matched).containsExactlyInAnyOrder(120, 122, 123, 134);

        // 101: 4학년 요건을 못 맞춤 → 지원 불가(포트폴리오 필수 줄은 그대로 남는다)
        Map<String, Object> job101 = job(body, 101);
        assertThat(job101.get("verdict")).isEqualTo("INELIGIBLE");
        assertThat(reason(job101, "학년")).containsEntry("requirement", "4학년").containsEntry("mine", "3학년")
                .containsEntry("result", "NOT_MET");
        assertThat(reason(job101, "포트폴리오")).containsEntry("result", "CHECK");

        // 117: 졸업예정자 요건 + 학점 3.5 이상 → 둘 다 못 맞춰 지원 불가
        Map<String, Object> job117 = job(body, 117);
        assertThat(job117.get("verdict")).isEqualTo("INELIGIBLE");
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
    void 선호_전공이_콕_집은_학과와_같은_묶음이면_가까운_전공이다() throws Exception {
        // ADR-0028: 컴퓨터공학과(26) — 119는 '소프트웨어학과'만 적어 가까운 전공, 136은 '이공계열'이라 선호 전공 안
        String cs = check(guestToken("STUDENT"), profile(26, 3, 5, "4.0", false, "[]"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(job(cs, 119).get("majorMatch")).isEqualTo("NEAR");
        assertThat(job(cs, 119).get("verdict")).isEqualTo("ELIGIBLE");
        assertThat(job(cs, 136).get("majorMatch")).isEqualTo("MATCH");
        // 경영학부(5)는 금융정보공학과와 같은 묶음이지만 136은 계열 표기('이공계열')라 가까운 전공으로 보지 않는다.
        // 군사학과(15)는 어느 묶음에도 없어 소서 국내 마케팅(122)이 선호 전공 밖
        String biz = check(guestToken("STUDENT"), profile(5, 3, 5, "4.0", false, "[]"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(job(biz, 136).get("majorMatch")).isEqualTo("NOT_LISTED");
        String army = check(guestToken("STUDENT"), profile(15, 3, 5, "4.0", false, "[]"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(job(army, 122).get("majorMatch")).isEqualTo("NOT_LISTED");
        assertThat(JsonPath.<List<String>>read(army, "$.jobs[*].majorMatch")).doesNotContain("NEAR");
    }

    @Test
    void 기준일에_마감된_직무는_같은_판정의_맨_아래로() throws Exception {
        // ADR-0027: 광고홍보콘텐츠학과(10) 4학년 — 101 AE는 리스트 1번이지만 7/18 마감이라 같은 판정 묶음의 맨 끝
        String body = check(guestToken("STUDENT"), profile(10, 4, 7, "4.0", false, "[]"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String verdict = JsonPath.<List<String>>read(body, "$.jobs[?(@.jobId == 101)].verdict").getFirst();
        assertThat(verdict).isNotEqualTo("INELIGIBLE");
        List<Integer> group = JsonPath.read(body, "$.jobs[?(@.verdict == '" + verdict + "')].jobId");
        assertThat(group.size()).isGreaterThan(1);
        assertThat(group.getLast()).isEqualTo(101);
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
    void 자격증을_모두_가진_졸업예정_4학년_평점_4점5면_37개_지원_가능() throws Exception {
        check(memberToken(), profile(MAKEUP, 4, 7, "4.5", true, "[\"ACCOUNTING\", \"BEAUTY\", \"BEAUTY\"]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.eligible").value(37))
                .andExpect(jsonPath("$.summary.needsCheck").value(3))
                .andExpect(jsonPath("$.summary.ineligible").value(0));
    }

    @Test
    void 자격증을_답하지_않으면_없음으로_보고_필수_자격증_직무는_지원_불가() throws Exception {
        // ADR-0024: 프로필에 없으면(certificates 빠짐 = null 포함) 없음이다 — []와 같은 결과
        String body = check(memberToken(), profile(MAKEUP, 3, 5, "3.4", false))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.eligible").value(22))
                .andExpect(jsonPath("$.summary.needsCheck").value(1))
                .andExpect(jsonPath("$.summary.ineligible").value(17))
                .andReturn().getResponse().getContentAsString();
        assertThat(job(body, 140).get("verdict")).isEqualTo("INELIGIBLE");
        assertThat(reason(job(body, 140), "자격증")).containsEntry("mine", "없음").containsEntry("result", "NOT_MET");
        // 미용 자격증이 있으면 지원 가능
        String has = check(memberToken(), profile(MAKEUP, 3, 5, "3.4", false, "[\"BEAUTY\"]"))
                .andExpect(jsonPath("$.summary.eligible").value(23))
                .andReturn().getResponse().getContentAsString();
        assertThat(reason(job(has, 140), "자격증")).containsEntry("mine", "있음").containsEntry("result", "MET");
    }

    @Test
    void 자격증_코드가_코드표에_없으면_400_profile_certificates() throws Exception {
        String token = memberToken();
        for (String bad : List.of("[\"NOPE\"]", "[null]", "[\"BEAUTY\", \"beauty\"]")) {
            check(token, profile(MAKEUP, 3, 5, "3.4", false, bad))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                    .andExpect(jsonPath("$.fields[0].field").value("profile.certificates"));
        }
        String many = "[" + String.join(",", java.util.Collections.nCopies(21, "\"BEAUTY\"")) + "]";
        check(token, profile(MAKEUP, 3, 5, "3.4", false, many))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields[0].field").value("profile.certificates"));
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

    /** certificates: JSON 배열 글(예: "[]", "[\"BEAUTY\"]"). */
    private static String profile(int departmentId, int grade, int semesters, String gpa, boolean graduating,
                                  String certificates) {
        return """
                {"profile": {"departmentId": %d, "grade": %d, "completedSemesters": %d, "gpa": %s,
                 "graduationExpected": %s, "interestText": "뷰티 브랜드 SNS 마케팅", "homeAreaCode": "11350",
                 "certificates": %s}}"""
                .formatted(departmentId, grade, semesters, gpa, graduating, certificates);
    }

    /** citation 기대값(page가 null일 수 있어 Map.of 대신). */
    private static Map<String, Object> cite(String sourceType, String documentTitle, Integer page, String quote) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("sourceType", sourceType);
        m.put("documentTitle", documentTitle);
        m.put("page", page);
        m.put("quote", quote);
        return m;
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
