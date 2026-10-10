package kr.ac.skuniv.coopradar.internship;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import kr.ac.skuniv.coopradar.TestcontainersConfiguration;
import kr.ac.skuniv.coopradar.auth.JwtService;
import kr.ac.skuniv.coopradar.auth.Role;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * docs/api #37~#58 현장실습 진행(ADR-0033) — 체험 학생이 지원서를 쓰고 학과(부)장 승인을 받아 내면, 같은 묶음의 체험 센터가
 * 접수 → 매칭 → 선발 → 마무리까지 처리하는 흐름. 다른 묶음·가입 센터와는 섞이지 않는다.
 * 2026-2 시드: 소서 국내 마케팅 122 · AMD 120, 세정 국내 인플루언서 103. 예시 프로필은 메이크업디자인학과 3학년.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class InternshipFlowApiTest {

    private static final AtomicInteger IP = new AtomicInteger(1);
    static final String ESSAY = "실습에서 맡을 일을 미리 정리해 보았습니다. ".repeat(20);

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient db;

    @Autowired
    JwtService jwt;

    @Test
    void 지원서를_쓰고_승인받아_내면_같은_묶음_센터가_접수_매칭_선발_마무리까지_처리한다() throws Exception {
        Guest student = guest("STUDENT", "APPLYING", null);
        Guest center = guest("CENTER", null, student.group());
        assertThat(student.today()).isEqualTo("2026-07-23");

        // 담은 직무 순위 → 1~3지망(저장한 예시 프로필로 판정)
        for (int job : new int[] {122, 120, 103}) {
            perform(post("/api/me/plan/items"), student, "{\"jobId\": " + job + "}").andExpect(status().is2xxSuccessful());
        }
        perform(put("/api/me/plan/ranks"), student,
                "{\"ranks\": [{\"jobId\": 122, \"rank\": 1}, {\"jobId\": 120, \"rank\": 2}, {\"jobId\": 103, \"rank\": 3}]}")
                .andExpect(status().isOk());
        String empty = perform(get("/api/me/application"), student, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NONE"))
                .andExpect(jsonPath("$.applicationId").isEmpty())
                .andExpect(jsonPath("$.period.open").value(true))
                .andExpect(jsonPath("$.academic.department.name").value("메이크업디자인학과"))
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<Integer>>read(empty, "$.picks[*].jobId")).containsExactly(122, 120, 103);
        assertThat(JsonPath.<List<String>>read(empty, "$.picks[*].verdict")).doesNotContain("INELIGIBLE").doesNotContainNull();

        // 동의 없이는 저장하지 않는다
        perform(put("/api/me/application"), student, "{\"consents\": {\"collect\": false, \"thirdParty\": true}}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CONSENT_REQUIRED"));
        perform(post("/api/me/application/submit"), student, null)
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("APPLICATION_NOT_FOUND"));

        String draft = perform(put("/api/me/application"), student, form(ESSAY + " 소서"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.essayWarnings[0].institutions[0]").value("소서"))
                .andExpect(jsonPath("$.approval.status").value("NONE"))
                .andReturn().getResponse().getContentAsString();
        dump("me-application.json", draft);
        shape(draft, "getMyApplication");
        // 승인 없이 내면 빠진 것을 알려 준다
        perform(post("/api/me/application/submit"), student, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("APPLICATION_INCOMPLETE"))
                .andExpect(jsonPath("$.fields[0].field").value("APPROVAL"));

        // 학과(부)장 승인 링크(공개) → 승인
        String requested = perform(post("/api/me/application/approval"), student, null)
                .andExpect(jsonPath("$.approval.status").value("REQUESTED"))
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(requested, "$.approval.token");
        String approval = mvc.perform(get("/api/approvals/" + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("APPLICATION"))
                .andExpect(jsonPath("$.student.nameKo").value("예시 학생"))
                .andExpect(jsonPath("$.picks.length()").value(3))
                .andReturn().getResponse().getContentAsString();
        assertThat(approval).doesNotContain("010-").doesNotContain("노원구");
        dump("approval.json", mvc.perform(post("/api/approvals/" + token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED")).andReturn().getResponse().getContentAsString());
        mvc.perform(get("/api/approvals/" + "0".repeat(32))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("APPROVAL_NOT_FOUND"));

        // 승인 뒤 내용이 바뀌면 다시 받아야 한다
        perform(put("/api/me/application"), student, form(ESSAY)).andExpect(jsonPath("$.approval.status").value("STALE"));
        mvc.perform(post("/api/approvals/" + token)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
        approve(student);
        String submitted = perform(post("/api/me/application/submit"), student, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.receiptNo").value("2026-2-007"))
                .andExpect(jsonPath("$.academic.grade").value(3))
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(submitted, "$.checklist[*].done").toString()).doesNotContain("false");
        perform(put("/api/me/application"), student, form(ESSAY))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("APPLICATION_LOCKED"));

        // 접수함: 가상 6명 + 이 학생. 다른 묶음 센터는 이 학생을 못 본다
        String inbox = perform(get("/api/center/applications"), center, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.counts.all").value(7))
                .andExpect(jsonPath("$.counts.submitted").value(3))
                .andExpect(jsonPath("$.counts.fixRequested").value(1))
                .andReturn().getResponse().getContentAsString();
        dump("center-applications.json", inbox);
        shape(inbox, "getCenterApplications");
        long mine = ((Number) JsonPath.read(submitted, "$.applicationId")).longValue();
        Guest other = guest("CENTER", null, null);
        perform(get("/api/center/applications"), other, null).andExpect(jsonPath("$.counts.all").value(6));
        perform(get("/api/center/applications/" + mine), other, null).andExpect(status().isNotFound());
        String detail = perform(get("/api/center/applications/" + mine), center, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applicant.phone").value("010-0000-0000"))
                .andExpect(jsonPath("$.approval.token").isEmpty())
                .andReturn().getResponse().getContentAsString();
        dump("center-application.json", detail);

        // 보완 요청(이유 필수) → 학생이 고쳐 다시 냄(같은 접수번호) → 접수
        perform(put("/api/center/applications/" + mine + "/status"), center, "{\"status\": \"FIX_REQUESTED\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields[0].field").value("reason"));
        perform(put("/api/center/applications/" + mine + "/status"), center,
                "{\"status\": \"FIX_REQUESTED\", \"reason\": \"자기소개서 1번에 기관 이름을 빼 주세요.\"}")
                .andExpect(jsonPath("$.status").value("FIX_REQUESTED"));
        perform(get("/api/me/application"), student, null)
                .andExpect(jsonPath("$.fixReason").value("자기소개서 1번에 기관 이름을 빼 주세요."));
        perform(put("/api/me/application"), student, form(ESSAY + " 다시")).andExpect(status().isOk());
        approve(student);
        perform(post("/api/me/application/submit"), student, null)
                .andExpect(jsonPath("$.status").value("SUBMITTED")).andExpect(jsonPath("$.receiptNo").value("2026-2-007"));
        perform(put("/api/center/applications/" + mine + "/status"), center, "{\"status\": \"RECEIVED\"}")
                .andExpect(jsonPath("$.status").value("RECEIVED"));

        // 매칭: 접수 완료한 사람을 모두 골라야 확정된다
        perform(post("/api/center/placement/confirm"), center, null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
        String board = perform(get("/api/center/placement"), center, null).andReturn().getResponse().getContentAsString();
        for (Object id : JsonPath.<List<Object>>read(board, "$.applicants[?(@.status == 'RECEIVED')].applicationId")) {
            perform(put("/api/center/applications/" + id + "/match"), center, "{\"rank\": 1}").andExpect(status().isOk());
        }
        perform(put("/api/center/applications/" + mine + "/match"), center, "{\"rank\": 4}")
                .andExpect(status().isBadRequest());
        String confirmed = perform(post("/api/center/placement/confirm"), center, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.confirmedAt").isNotEmpty())
                .andExpect(jsonPath("$.counts.matched").value(4))
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(confirmed, "$.jobs[*].title")).contains("국내 마케팅");
        perform(put("/api/center/applications/" + mine + "/status"), center, "{\"status\": \"RECEIVED\"}")
                .andExpect(status().isConflict());
        perform(get("/api/me/internship"), student, null)
                .andExpect(jsonPath("$.placement.jobId").value(122))
                .andExpect(jsonPath("$.placement.result").isEmpty());

        // 선발: 대기가 있으면 알릴 수 없다 → 결과를 넣고 알림
        perform(post("/api/center/placement/notify"), center, null).andExpect(status().isConflict());
        for (Object id : JsonPath.<List<Object>>read(confirmed, "$.applicants[?(@.status == 'MATCHED')].applicationId")) {
            perform(put("/api/center/applications/" + id + "/selection"), center,
                    "{\"interviewAt\": \"2026-07-30T14:30:00+09:00\", \"interviewMode\": \"IN_PERSON\", \"result\": \"PASS\"}")
                    .andExpect(status().isOk());
        }
        String notified = perform(post("/api/center/placement/notify"), center, null)
                .andExpect(jsonPath("$.counts.pass").value(4))
                .andReturn().getResponse().getContentAsString();
        dump("center-placement.json", notified);
        shape(notified, "getCenterPlacement");

        // 마무리: 수행결과보고서는 커리어 리포트가 있어야 낸다
        perform(put("/api/me/internship/documents/REPORT"), student, null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CAREER_REPORT_REQUIRED"));
        perform(put("/api/me/internship/documents/CREDIT"), student, null)
                .andExpect(jsonPath("$.documents.credit").isNotEmpty());
        perform(put("/api/me/internship/documents/SURVEY"), student, null).andExpect(status().isOk());
        perform(post("/api/me/career-report"), student, "{\"jobId\": 122, \"consent\": true, \"practiceText\": \""
                + "인스타그램 계정의 주간 게시물 성과를 표로 정리해 팀 회의에서 공유했습니다. ".repeat(4) + "\"}")
                .andExpect(status().isOk());
        perform(put("/api/me/internship/documents/REPORT"), student, null)
                .andExpect(jsonPath("$.documents.careerReportId").isNotEmpty());

        String close = perform(get("/api/center/close"), center, null)
                .andExpect(jsonPath("$.counts.students").value(4))
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(close, "$.rows[?(@.applicationId == " + mine + ")].missing[*]"))
                .containsExactly("EVALUATION", "ATTENDANCE", "APPROVAL");
        String after = perform(put("/api/center/close/" + mine), center, "{\"evaluation\": true, \"attendance\": true}")
                .andReturn().getResponse().getContentAsString();
        String creditToken = JsonPath.<List<String>>read(after, "$.rows[?(@.applicationId == " + mine + ")].approval.token")
                .get(0);
        mvc.perform(get("/api/approvals/" + creditToken)).andExpect(jsonPath("$.kind").value("CREDIT"))
                .andExpect(jsonPath("$.placement.title").value("국내 마케팅"));
        mvc.perform(post("/api/approvals/" + creditToken)).andExpect(status().isOk());
        String done = perform(get("/api/center/close"), center, null).andReturn().getResponse().getContentAsString();
        dump("center-close.json", done);
        shape(done, "getCenterClose");
        assertThat(JsonPath.<List<Boolean>>read(done, "$.rows[?(@.applicationId == " + mine + ")].ready")).containsExactly(true);
        dump("center-close-remind.json", perform(post("/api/center/close/remind"), center, null)
                .andExpect(jsonPath("$.reminded").value(3)).andReturn().getResponse().getContentAsString());
        String csv = perform(get("/api/center/close/credits.csv"), center, null)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(csv).startsWith("﻿접수번호,학번,성명").contains("\"2026-2-007\"").contains("\"예시 학생\"")
                .contains("\"표준 현장실습 D\",12");
        String internship = perform(get("/api/me/internship"), student, null)
                .andExpect(jsonPath("$.placement.result").value("PASS"))
                .andExpect(jsonPath("$.documents.creditApproval.status").value("APPROVED"))
                .andReturn().getResponse().getContentAsString();
        dump("me-internship-applying.json", internship);

        // 현황판: 처리할 것(이 묶음) · 학생이 찾는 직무
        perform(get("/api/center/board"), center, null)
                .andExpect(jsonPath("$.todo.newApplications").value(2))
                .andExpect(jsonPath("$.todo.fixRequested").value(1))
                .andExpect(jsonPath("$.demand.rows.length()").value(25))
                .andExpect(jsonPath("$.rows[0].views").isNumber());

        // 지우기: 매칭 확정 뒤에는 못 지운다
        perform(delete("/api/me/application"), student, null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("APPLICATION_LOCKED"));
    }

    @Test
    void 실습_중_체험_학생은_지난_기록과_실습_주차를_갖고_시작한다() throws Exception {
        Guest student = guest("STUDENT", "PRACTICING", null);
        assertThat(student.today()).isEqualTo("2026-10-14");
        String body = perform(get("/api/me/internship"), student, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.asOf").value("2026-10-14"))
                .andExpect(jsonPath("$.now").value("PRACTICE"))
                .andExpect(jsonPath("$.next.code").value("MIDCHECK"))
                .andExpect(jsonPath("$.next.kind").value("START"))
                .andExpect(jsonPath("$.next.days").value(5))
                .andExpect(jsonPath("$.application.virtual").value(true))
                .andExpect(jsonPath("$.placement.result").value("PASS"))
                .andExpect(jsonPath("$.placement.practice.day").value(44))
                .andExpect(jsonPath("$.placement.practice.days").value(103))
                .andExpect(jsonPath("$.placement.practice.week").value(7))
                .andExpect(jsonPath("$.placement.practice.weeks").value(15))
                .andExpect(jsonPath("$.placement.practice.currentPlan.weeks").value("7~8주차"))
                .andExpect(jsonPath("$.placement.practice.credits").value(12))
                .andExpect(jsonPath("$.documents.report").isEmpty())
                .andReturn().getResponse().getContentAsString();
        dump("me-internship.json", body);
        shape(body, "getMyInternship");
        assertThat(JsonPath.<List<String>>read(body, "$.stages[*].state"))
                .containsExactly("DONE", "DONE", "DONE", "DONE", "DONE", "DONE", "NOW", "NEXT", "NEXT", "NEXT", "NEXT");
        assertThat(JsonPath.<List<Integer>>read(body, "$.picks[*].jobId")).containsExactly(122, 120, 103, 123);
        // asOf로 다른 날을 볼 수 있다
        perform(get("/api/me/internship?asOf=2026-07-25"), student, null)
                .andExpect(jsonPath("$.now").value("MATCH"))
                .andExpect(jsonPath("$.next.code").value("MATCH"));
        // 마친 뒤: 기관 서류는 받았고 학점 인정 승인은 대기, 보고서는 학생이 직접(커리어 리포트)
        Guest done = guest("STUDENT", "DONE", null);
        perform(get("/api/me/internship"), done, null)
                .andExpect(jsonPath("$.now").value("DEBRIEF"))
                .andExpect(jsonPath("$.documents.evaluation").isNotEmpty())
                .andExpect(jsonPath("$.documents.credit").isNotEmpty())
                .andExpect(jsonPath("$.documents.report").isEmpty())
                .andExpect(jsonPath("$.documents.creditApproval.status").value("REQUESTED"));
    }

    @Test
    void 시연_버튼은_체험_센터의_묶음만_마무리까지_진행하고_가입_센터는_403() throws Exception {
        Guest center = guest("CENTER", null, null);
        String advanced = perform(post("/api/center/demo/advance"), center, "{\"to\": \"CLOSING\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.to").value("CLOSING"))
                .andReturn().getResponse().getContentAsString();
        dump("center-demo-advance.json", advanced);
        String close = perform(get("/api/center/close"), center, null)
                .andExpect(jsonPath("$.counts.students").value(4))
                .andReturn().getResponse().getContentAsString();
        // 가상 지원자 1은 불합격, 4는 보완 중이라 마무리에 없다. 2·6은 서류·승인까지
        assertThat(JsonPath.<List<String>>read(close, "$.rows[*].student.nameKo"))
                .containsExactly("가상 지원자 2", "가상 지원자 3", "가상 지원자 5", "가상 지원자 6");
        assertThat(JsonPath.<List<Boolean>>read(close, "$.rows[*].ready")).containsExactly(true, false, false, true);
        perform(get("/api/center/placement"), center, null).andExpect(jsonPath("$.counts.fail").value(1));

        long id = db.sql("INSERT INTO app_user (email, password_hash, role) VALUES (:e, 'x', 'CENTER') RETURNING id")
                .param("e", "center." + UUID.randomUUID() + "@example.com").query(Long.class).single();
        String member = jwt.issue(id, Role.CENTER, false, Instant.now().plusSeconds(600));
        mvc.perform(post("/api/center/demo/advance").header("Authorization", "Bearer " + member)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"to\": \"MATCHED\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("DEMO_ONLY"));
        // 가입 센터는 체험 묶음을 보지 않는다
        mvc.perform(get("/api/center/applications").header("Authorization", "Bearer " + member))
                .andExpect(jsonPath("$.items[?(@.virtual == true)]").isEmpty());
        // 학생은 센터 API를 못 쓴다
        perform(get("/api/center/placement"), guest("STUDENT", null, null), null).andExpect(status().isForbidden());
    }

    @Test
    void 지원서_입력_형식과_신청_기간을_본다() throws Exception {
        Guest student = guest("STUDENT", "APPLYING", null);
        perform(put("/api/me/application"), student,
                "{\"applicant\": {\"gender\": \"X\", \"phone\": \"전화\"}, \"consents\": {\"collect\": true}}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        perform(put("/api/me/application"), student, "{\"essays\": [\"a\"], \"consents\": {\"collect\": true}}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields[0].field").value("essays"));
        perform(put("/api/me/internship/documents/REPORT"), student, null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
        perform(put("/api/me/internship/documents/PHOTO"), student, null).andExpect(status().isBadRequest());
        // 기준일이 신청 기간 밖(실습 중 체험 학생 10/14)이면 낼 수 없다 — 지난 기록은 이미 냄이라 잠김
        Guest practicing = guest("STUDENT", "PRACTICING", null);
        perform(post("/api/me/application/submit"), practicing, null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("APPLICATION_LOCKED"));
        perform(get("/api/me/application"), practicing, null)
                .andExpect(jsonPath("$.period.open").value(false))
                .andExpect(jsonPath("$.virtual").value(true))
                .andExpect(jsonPath("$.status").value("MATCHED"));
        // 가입 학생은 오늘(한국 시간)로 본다 — 신청 기간(7/13~7/24) 밖이면 낼 수 없다
        String signup = mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"apply." + UUID.randomUUID() + "@example.com\",\"password\":\"password-8\"}"))
                .andReturn().getResponse().getContentAsString();
        String member = JsonPath.read(signup, "$.accessToken");
        perform(put("/api/me/application"), member, form(ESSAY)).andExpect(status().isOk());
        if (!java.time.LocalDate.now(kr.ac.skuniv.coopradar.common.Times.KST).isAfter(java.time.LocalDate.of(2026, 7, 24))) {
            return; // 신청 기간 전·중에 돌리면 아래 확인은 건너뛴다
        }
        perform(post("/api/me/application/submit"), member, null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("APPLICATION_CLOSED"));
        perform(put("/api/me/application"), student, form(ESSAY)).andExpect(status().isOk());
        perform(delete("/api/me/application"), student, null).andExpect(status().isNoContent());
        perform(get("/api/me/application"), student, null).andExpect(jsonPath("$.status").value("NONE"));
        // 센터는 학생 API를 못 쓴다
        perform(get("/api/me/application"), guest("CENTER", null, null), null).andExpect(status().isForbidden());
    }

    // ───────── 도우미 ─────────

    record Guest(String token, String group, String today) {
    }

    private Guest guest(String role, String stage, String group) throws Exception {
        String body = "{\"role\":\"" + role + "\"" + (stage == null ? "" : ",\"stage\":\"" + stage + "\"")
                + (group == null ? "" : ",\"demoGroup\":\"" + group + "\"") + "}";
        String res = mvc.perform(post("/api/auth/guest").header("CF-Connecting-IP", "198.51.100." + (100 + IP.getAndIncrement()))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        if ("STUDENT".equals(role) && "PRACTICING".equals(stage)) {
            dump("auth-guest.json", res);
        }
        return new Guest(JsonPath.read(res, "$.accessToken"), JsonPath.read(res, "$.demoGroup"),
                JsonPath.read(res, "$.demoToday"));
    }

    private ResultActions perform(MockHttpServletRequestBuilder req, Guest who, String json) throws Exception {
        return perform(req, who.token(), json);
    }

    private ResultActions perform(MockHttpServletRequestBuilder req, String token, String json) throws Exception {
        req.header("Authorization", "Bearer " + token);
        if (json != null) {
            req.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mvc.perform(req);
    }

    private void approve(Guest student) throws Exception {
        String body = perform(post("/api/me/application/approval"), student, null).andReturn().getResponse()
                .getContentAsString();
        mvc.perform(post("/api/approvals/" + JsonPath.read(body, "$.approval.token"))).andExpect(status().isOk());
    }

    static String form(String essay) {
        String e = essay.replace("\"", "");
        return """
                {"applicant": {"nameKo": "예시 학생", "nameEn": "Yesi Haksaeng", "birthDate": "2004-03-15", "gender": "F",
                  "phone": "010-0000-0000", "email": null, "address": "서울특별시 노원구 (예시)", "studentNo": "2023000000",
                  "minorMajor": null},
                 "resume": {"certificates": [{"kind": "CERTIFICATE", "name": "컴퓨터활용능력 2급", "issuer": "대한상공회의소",
                   "acquiredOn": "2025-08"}], "awards": [], "careers": [{"period": "2025.03 ~ 2025.10",
                   "company": "화장품 매장(예시)", "role": "판매 아르바이트", "note": null}]},
                 "essays": ["%s", "%s", "%s", "%s"], "pledge": true,
                 "consents": {"collect": true, "thirdParty": true}, "signature": "예시 학생"}
                """.formatted(e, ESSAY, ESSAY, ESSAY);
    }

    /** 계약 예시와 모양 비교. 예시를 만드는 중(DUMP_EXAMPLES)에는 건너뛴다. */
    static void shape(String body, String operationId) {
        if (System.getenv("DUMP_EXAMPLES") == null) {
            Contract.assertSameShape(body, Contract.responseExample(operationId, 200, null));
        }
    }

    /** DUMP_EXAMPLES=폴더 로 돌리면 응답을 파일로 남긴다(docs/api 예시를 만들 때만). */
    static void dump(String name, String body) throws Exception {
        String dir = System.getenv("DUMP_EXAMPLES");
        if (dir != null && !dir.isBlank()) {
            Files.createDirectories(Path.of(dir));
            Files.writeString(Path.of(dir, name), body, StandardCharsets.UTF_8);
        }
    }
}
