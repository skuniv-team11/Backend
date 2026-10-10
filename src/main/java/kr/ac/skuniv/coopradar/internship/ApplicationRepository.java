package kr.ac.skuniv.coopradar.internship;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import kr.ac.skuniv.coopradar.common.Times;
import kr.ac.skuniv.coopradar.eligibility.EligibilityDtos.Verdict;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.Applicant;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.ApprovalKind;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.InterviewMode;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.Result;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.Resume;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.Status;
import kr.ac.skuniv.coopradar.job.InstitutionRef;
import kr.ac.skuniv.coopradar.job.Stipend;
import kr.ac.skuniv.coopradar.me.ProfileView.DepartmentRef;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 지원서·승인·마무리 서류 SQL(V11, ADR-0033). 지원서의 개인정보는 로그에 남기지 않는다(ADR-0008).
 * 센터 조회는 {@link Scope}로 묶음을 나눈다: 체험 계정은 자기 묶음만, 가입 계정은 묶음이 없는 지원서만.
 */
@Repository
public class ApplicationRepository {

    static final ObjectMapper JSON = JsonMapper.builder().build();

    private final JdbcClient db;

    public ApplicationRepository(JdbcClient db) {
        this.db = db;
    }

    /** 센터·학생이 보는 지원서 범위. demo면 그 묶음만(group이 null이면 아무것도), 아니면 묶음 없는 것만. */
    record Scope(boolean demo, UUID group) {

        static Scope of(Demo demo) {
            return new Scope(demo.guest(), demo.group());
        }

        String where(String alias) {
            return demo ? alias + ".demo_group_id = :group" : alias + ".demo_group_id IS NULL";
        }
    }

    /** 계정의 체험 정보. 가입 계정은 guest false · group null · today null. */
    record Demo(boolean guest, UUID group, LocalDate today, String email) {
    }

    Demo demo(long userId) {
        return db.sql("SELECT is_guest, demo_group_id, demo_today, email FROM app_user WHERE id = :id")
                .param("id", userId)
                .query((rs, n) -> new Demo(rs.getBoolean("is_guest"), rs.getObject("demo_group_id", UUID.class),
                        rs.getObject("demo_today", LocalDate.class), rs.getString("email")))
                .single();
    }

    // ───────────── 지원서 행 ─────────────

    record Row(long id, int roundId, Long userId, UUID group, boolean virtual, Status status, String receiptNo,
               Applicant applicant, Integer departmentId, String departmentName, Integer grade, Integer semesters,
               BigDecimal gpa, Boolean graduationExpected, Resume resume, List<String> essays, boolean pledge,
               boolean consentCollect, boolean consentThirdParty, String signature, int counselCount, String fixReason,
               Integer matchedRank, OffsetDateTime matchedAt, OffsetDateTime interviewAt, InterviewMode interviewMode,
               Result result, OffsetDateTime notifiedAt, OffsetDateTime createdAt, OffsetDateTime updatedAt,
               OffsetDateTime submittedAt, OffsetDateTime receivedAt) {

        DepartmentRef department() {
            return departmentId == null ? null : new DepartmentRef(departmentId, departmentName);
        }
    }

    private static final String ROW_SQL = """
            SELECT a.*, d.name AS department_name FROM application a LEFT JOIN department d ON d.id = a.department_id
            """;

    private static Row row(ResultSet rs, int n) throws SQLException {
        String mode = rs.getString("interview_mode");
        return new Row(rs.getLong("id"), rs.getInt("round_id"), (Long) rs.getObject("user_id"),
                rs.getObject("demo_group_id", UUID.class), rs.getBoolean("is_virtual"), Status.valueOf(rs.getString("status")),
                rs.getString("receipt_no"),
                new Applicant(rs.getString("name_ko"), rs.getString("name_en"), rs.getObject("birth_date", LocalDate.class),
                        rs.getString("gender"), rs.getString("phone"), rs.getString("email"), rs.getString("address"),
                        rs.getString("student_no"), rs.getString("minor_major")),
                (Integer) rs.getObject("department_id"), rs.getString("department_name"), (Integer) rs.getObject("grade"),
                (Integer) rs.getObject("completed_semesters"), rs.getBigDecimal("gpa"),
                (Boolean) rs.getObject("graduation_expected"),
                JSON.readValue(rs.getString("resume"), Resume.class), strings(rs.getArray("essays")),
                rs.getBoolean("pledge"), rs.getBoolean("consent_collect"), rs.getBoolean("consent_third_party"),
                rs.getString("signature"), rs.getInt("counsel_count"), rs.getString("fix_reason"),
                (Integer) rs.getObject("matched_rank"), time(rs, "matched_at"), time(rs, "interview_at"),
                mode == null ? null : InterviewMode.valueOf(mode), Result.valueOf(rs.getString("result")),
                time(rs, "result_notified_at"), time(rs, "created_at"), time(rs, "updated_at"), time(rs, "submitted_at"),
                time(rs, "received_at"));
    }

    Optional<Row> byUser(long userId, int roundId) {
        return db.sql(ROW_SQL + " WHERE a.user_id = :user AND a.round_id = :round")
                .param("user", userId).param("round", roundId).query(ApplicationRepository::row).optional();
    }

    /** 학생이 고치는 경로: 행을 잠가 같은 지원서를 두 요청이 동시에 바꾸지 않게 한다. */
    Optional<Row> byUserForUpdate(long userId, int roundId) {
        return db.sql(ROW_SQL + " WHERE a.user_id = :user AND a.round_id = :round FOR UPDATE OF a")
                .param("user", userId).param("round", roundId).query(ApplicationRepository::row).optional();
    }

    Optional<Row> byId(long id) {
        return db.sql(ROW_SQL + " WHERE a.id = :id").param("id", id).query(ApplicationRepository::row).optional();
    }

    /** 센터가 바꾸는 경로: 행을 잠근다. */
    Optional<Row> byIdForUpdate(long id) {
        return db.sql(ROW_SQL + " WHERE a.id = :id FOR UPDATE OF a").param("id", id).query(ApplicationRepository::row)
                .optional();
    }

    /** 범위 안의 낸 지원서(작성 중 제외), 접수번호 순. */
    List<Row> inScope(Scope scope, int roundId) {
        return db.sql(ROW_SQL + " WHERE a.round_id = :round AND a.status <> 'DRAFT' AND " + scope.where("a")
                        + " ORDER BY a.receipt_no, a.id")
                .param("round", roundId).param("group", scope.group())
                .query(ApplicationRepository::row).list();
    }

    /** 작성 중 지원서를 만들고 잠근다. 같은 학생이 동시에 처음 저장해도 한 건만 생긴다. */
    Row insertDraft(long userId, int roundId, UUID group) {
        db.sql("""
                        INSERT INTO application (round_id, user_id, demo_group_id) VALUES (:round, :user, :group)
                        ON CONFLICT (user_id, round_id) WHERE user_id IS NOT NULL DO NOTHING""")
                .param("round", roundId).param("user", userId).param("group", group)
                .update();
        return byUserForUpdate(userId, roundId).orElseThrow();
    }

    /** 학생이 쓴 칸(신청서·이력서·자기소개서·서약·동의·서명). 고칠 수 있는 상태일 때만 바꾼다. 바뀐 행 수. */
    int saveForm(long id, Applicant a, Resume resume, List<String> essays, boolean pledge, boolean collect,
                 boolean thirdParty, String signature, Instant now) {
        return db.sql("""
                        UPDATE application SET name_ko = :nameKo, name_en = :nameEn, birth_date = :birth, gender = :gender,
                               phone = :phone, email = :email, address = :address, student_no = :studentNo,
                               minor_major = :minor, resume = CAST(:resume AS jsonb), essays = :essays, pledge = :pledge,
                               consent_collect = :collect, consent_third_party = :third, signature = :signature,
                               updated_at = :now
                        WHERE id = :id AND status IN ('DRAFT', 'FIX_REQUESTED')""")
                .param("nameKo", a.nameKo()).param("nameEn", a.nameEn()).param("birth", a.birthDate())
                .param("gender", a.gender()).param("phone", a.phone()).param("email", a.email())
                .param("address", a.address()).param("studentNo", a.studentNo()).param("minor", a.minorMajor())
                .param("resume", JSON.writeValueAsString(resume)).param("essays", essays.toArray(String[]::new))
                .param("pledge", pledge).param("collect", collect).param("third", thirdParty)
                .param("signature", signature).param("now", Times.utc(now)).param("id", id)
                .update();
    }

    void delete(long id) {
        db.sql("DELETE FROM application WHERE id = :id").param("id", id).update();
    }

    /** 다음 접수번호(회차·묶음 안에서 '2026-2-001'부터). 범위마다 트랜잭션 잠금을 잡아 두 학생이 같은 번호를 받지 않게 한다. */
    String nextReceiptNo(int roundId, UUID group, String termCode) {
        db.sql("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtext(:key))) l")
                .param("key", "receipt:" + roundId + ":" + (group == null ? "real" : group))
                .query(Integer.class).single();
        Integer max = db.sql("""
                        SELECT max(CAST(substring(receipt_no FROM '[0-9]+$') AS integer)) FROM application
                        WHERE round_id = :round AND receipt_no IS NOT NULL AND """
                        + (group == null ? " demo_group_id IS NULL" : " demo_group_id = :group"))
                .param("round", roundId).param("group", group)
                .query(Integer.class).optional().orElse(null);
        return "%s-%03d".formatted(termCode, (max == null ? 0 : max) + 1);
    }

    /** 내기: 접수번호·학적(저장한 프로필)·지망을 고정한다. 바뀐 행 수. */
    int submit(long id, String receiptNo, int departmentId, int grade, int semesters, BigDecimal gpa,
               boolean graduationExpected, Instant submittedAt, Instant now) {
        return db.sql("""
                        UPDATE application SET status = 'SUBMITTED', receipt_no = coalesce(receipt_no, :receipt),
                               department_id = :dep, grade = :grade, completed_semesters = :sem, gpa = :gpa,
                               graduation_expected = :grad, fix_reason = NULL, submitted_at = :submitted,
                               updated_at = :now
                        WHERE id = :id AND status IN ('DRAFT', 'FIX_REQUESTED')""")
                .param("receipt", receiptNo).param("dep", departmentId).param("grade", grade).param("sem", semesters)
                .param("gpa", gpa).param("grad", graduationExpected).param("submitted", Times.utc(submittedAt))
                .param("now", Times.utc(now)).param("id", id)
                .update();
    }

    /** 새로 들어온 지원서만 접수 완료로. 바뀐 행 수. */
    int receive(long id, Instant now) {
        return db.sql("""
                        UPDATE application SET status = 'RECEIVED', fix_reason = NULL, received_at = :now, updated_at = :now
                        WHERE id = :id AND status = 'SUBMITTED'""")
                .param("now", Times.utc(now)).param("id", id).update();
    }

    /** 낸 지원서(매칭 확정 전)에 보완 요청. 바뀐 행 수. */
    int requestFix(long id, String reason, Instant now) {
        return db.sql("""
                        UPDATE application SET status = 'FIX_REQUESTED', fix_reason = :reason, matched_rank = NULL,
                               updated_at = :now
                        WHERE id = :id AND status IN ('SUBMITTED', 'RECEIVED', 'FIX_REQUESTED')""")
                .param("reason", reason).param("now", Times.utc(now)).param("id", id).update();
    }

    /** 접수 완료(확정 전) 지원서의 매칭 지망. 바뀐 행 수. */
    int setMatchedRank(long id, Integer rank, Instant now) {
        return db.sql("UPDATE application SET matched_rank = :rank, updated_at = :now WHERE id = :id AND status = 'RECEIVED'")
                .param("rank", rank).param("now", Times.utc(now)).param("id", id).update();
    }

    /** 매칭 확정: 범위 안 접수 완료 + 매칭 고른 지원서를 MATCHED로. 바뀐 수. */
    int confirmMatches(Scope scope, int roundId, Instant now) {
        return db.sql("UPDATE application a SET status = 'MATCHED', matched_at = :now, updated_at = :now"
                        + " WHERE a.round_id = :round AND a.status = 'RECEIVED' AND a.matched_rank IS NOT NULL AND "
                        + scope.where("a"))
                .param("now", Times.utc(now)).param("round", roundId).param("group", scope.group()).update();
    }

    /** 매칭 확정 뒤, 결과를 알리기 전에만. 바뀐 행 수. */
    int setSelection(long id, OffsetDateTime interviewAt, InterviewMode mode, Result result, Instant now) {
        return db.sql("""
                        UPDATE application SET interview_at = :at, interview_mode = :mode, result = :result, updated_at = :now
                        WHERE id = :id AND status = 'MATCHED' AND result_notified_at IS NULL""")
                .param("at", interviewAt).param("mode", mode == null ? null : mode.name()).param("result", result.name())
                .param("now", Times.utc(now)).param("id", id).update();
    }

    /** 결과 알림: 범위 안 매칭 + 결과가 정해졌고 아직 안 알린 지원서. 바뀐 수. */
    int notifyResults(Scope scope, int roundId, Instant now) {
        return db.sql("UPDATE application a SET result_notified_at = :now, updated_at = :now"
                        + " WHERE a.round_id = :round AND a.status = 'MATCHED' AND a.result <> 'WAIT'"
                        + " AND a.result_notified_at IS NULL AND " + scope.where("a"))
                .param("now", Times.utc(now)).param("round", roundId).param("group", scope.group()).update();
    }

    // ───────────── 지망 ─────────────

    record PickRow(int rank, int jobId, Verdict verdict) {
    }

    /** 지망 순위와 직무. 순위는 담은 직무(#22)의 값 그대로다(2·3지망만 정했으면 2·3). */
    record RankedJob(int rank, int jobId) {
    }

    Map<Long, List<PickRow>> picks(List<Long> applicationIds) {
        Map<Long, List<PickRow>> out = new LinkedHashMap<>();
        if (applicationIds.isEmpty()) {
            return out;
        }
        db.sql("SELECT application_id, rank, job_id, verdict FROM application_pick WHERE application_id IN (:ids)"
                        + " ORDER BY application_id, rank")
                .param("ids", applicationIds)
                .query(rs -> {
                    out.computeIfAbsent(rs.getLong("application_id"), k -> new ArrayList<>())
                            .add(new PickRow(rs.getInt("rank"), rs.getInt("job_id"), Verdict.valueOf(rs.getString("verdict"))));
                });
        return out;
    }

    void replacePicks(long applicationId, List<PickRow> picks) {
        db.sql("DELETE FROM application_pick WHERE application_id = :id").param("id", applicationId).update();
        for (PickRow p : picks) {
            db.sql("INSERT INTO application_pick (application_id, rank, job_id, verdict) VALUES (:id, :rank, :job, :verdict)")
                    .param("id", applicationId).param("rank", p.rank()).param("job", p.jobId())
                    .param("verdict", p.verdict().name()).update();
        }
    }

    /** 담은 직무 순위(1~3지망). */
    List<RankedJob> rankedPlan(long userId) {
        return db.sql("SELECT rank, job_id FROM plan_item WHERE user_id = :user AND rank IS NOT NULL ORDER BY rank")
                .param("user", userId).query((rs, n) -> new RankedJob(rs.getInt("rank"), rs.getInt("job_id"))).list();
    }

    // ───────────── 직무 ─────────────

    record JobInfo(int id, String title, String team, InstitutionRef institution, int headcount, LocalDate periodStart,
                   LocalDate periodEnd, List<String> weekdays, String workHours, BigDecimal weeklyHours, Stipend stipend,
                   LocalDate closesOn) {
    }

    Map<Integer, JobInfo> jobs(List<Integer> ids) {
        Map<Integer, JobInfo> out = new LinkedHashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        db.sql("""
                        SELECT j.id, j.title, j.team, j.institution_id, i.name AS institution, j.headcount, j.period_start,
                               j.period_end, j.weekdays, j.work_hours_text, j.weekly_hours, j.stipend_basis,
                               j.stipend_amount, j.closes_on
                        FROM job j JOIN institution i ON i.id = j.institution_id WHERE j.id IN (:ids)""")
                .param("ids", ids)
                .query(rs -> {
                    out.put(rs.getInt("id"), new JobInfo(rs.getInt("id"), rs.getString("title"), rs.getString("team"),
                            new InstitutionRef(rs.getInt("institution_id"), rs.getString("institution")),
                            rs.getInt("headcount"), rs.getObject("period_start", LocalDate.class),
                            rs.getObject("period_end", LocalDate.class), strings(rs.getArray("weekdays")),
                            rs.getString("work_hours_text"), rs.getBigDecimal("weekly_hours"),
                            Stipend.of(rs.getString("stipend_basis"), (Integer) rs.getObject("stipend_amount")),
                            rs.getObject("closes_on", LocalDate.class)));
                });
        return out;
    }

    record WeekPlanRow(int seq, String weeks, String content) {
    }

    List<WeekPlanRow> weeklyPlans(int jobId) {
        return db.sql("SELECT seq, weeks_label, content FROM job_weekly_plan WHERE job_id = :job ORDER BY seq")
                .param("job", jobId)
                .query((rs, n) -> new WeekPlanRow(rs.getInt("seq"), rs.getString("weeks_label"), rs.getString("content")))
                .list();
    }

    record PlanRow(Integer rank, int jobId, String title, InstitutionRef institution, String ncsCode, String ncsName,
                   LocalDate closesOn) {
    }

    /** 담은 직무(1~3지망 → 후보 담은 순)와 NCS 세분류. */
    List<PlanRow> plan(long userId) {
        return db.sql("""
                        SELECT p.rank, j.id, j.title, j.institution_id, i.name AS institution, s.code, s.name, j.closes_on
                        FROM plan_item p
                        JOIN job j ON j.id = p.job_id
                        JOIN institution i ON i.id = j.institution_id
                        LEFT JOIN job_ncs n ON n.job_id = j.id
                        LEFT JOIN ncs_subcategory s ON s.code = n.subcategory_code
                        WHERE p.user_id = :user
                        ORDER BY p.rank NULLS LAST, p.added_at, j.id""")
                .param("user", userId)
                .query((rs, n) -> new PlanRow((Integer) rs.getObject("rank"), rs.getInt("id"), rs.getString("title"),
                        new InstitutionRef(rs.getInt("institution_id"), rs.getString("institution")), rs.getString("code"),
                        rs.getString("name"), rs.getObject("closes_on", LocalDate.class)))
                .list();
    }

    Optional<Long> careerReport(long userId, int jobId) {
        return db.sql("SELECT id FROM career_report WHERE user_id = :user AND job_id = :job")
                .param("user", userId).param("job", jobId).query(Long.class).optional();
    }

    // ───────────── 학과(부)장 승인 ─────────────

    record ApprovalRow(long applicationId, ApprovalKind kind, String token, String contentHash,
                       OffsetDateTime requestedAt, OffsetDateTime approvedAt) {
    }

    private static ApprovalRow approvalRow(ResultSet rs, int n) throws SQLException {
        return new ApprovalRow(rs.getLong("application_id"), ApprovalKind.valueOf(rs.getString("kind")),
                rs.getString("token"), rs.getString("content_hash"), time(rs, "requested_at"), time(rs, "approved_at"));
    }

    Optional<ApprovalRow> approval(long applicationId, ApprovalKind kind) {
        return db.sql("SELECT * FROM dept_approval WHERE application_id = :id AND kind = :kind")
                .param("id", applicationId).param("kind", kind.name()).query(ApplicationRepository::approvalRow).optional();
    }

    Map<Long, ApprovalRow> approvals(List<Long> ids, ApprovalKind kind) {
        Map<Long, ApprovalRow> out = new LinkedHashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        db.sql("SELECT * FROM dept_approval WHERE application_id IN (:ids) AND kind = :kind")
                .param("ids", ids).param("kind", kind.name())
                .query((ResultSet rs) -> {
                    ApprovalRow r = approvalRow(rs, 0);
                    out.put(r.applicationId(), r);
                });
        return out;
    }

    Optional<ApprovalRow> approvalByToken(String token) {
        return db.sql("SELECT * FROM dept_approval WHERE token = :token")
                .param("token", token).query(ApplicationRepository::approvalRow).optional();
    }

    /** 승인 요청(이미 있으면 새 링크·새 내용으로 바꾸고 승인 전으로 되돌린다). */
    void requestApproval(long applicationId, ApprovalKind kind, String token, String contentHash, Instant now) {
        db.sql("""
                        INSERT INTO dept_approval (application_id, kind, token, content_hash, requested_at)
                        VALUES (:id, :kind, :token, :hash, :now)
                        ON CONFLICT (application_id, kind) DO UPDATE
                            SET token = EXCLUDED.token, content_hash = EXCLUDED.content_hash,
                                requested_at = EXCLUDED.requested_at, approved_at = NULL""")
                .param("id", applicationId).param("kind", kind.name()).param("token", token).param("hash", contentHash)
                .param("now", Times.utc(now)).update();
    }

    void approve(String token, Instant now) {
        db.sql("UPDATE dept_approval SET approved_at = :now WHERE token = :token AND approved_at IS NULL")
                .param("now", Times.utc(now)).param("token", token).update();
    }

    /** 체험 데이터용: 승인까지 한 번에. */
    void insertApproved(long applicationId, ApprovalKind kind, String token, String contentHash, Instant requestedAt,
                        Instant approvedAt) {
        db.sql("""
                        INSERT INTO dept_approval (application_id, kind, token, content_hash, requested_at, approved_at)
                        VALUES (:id, :kind, :token, :hash, :req, :ok)""")
                .param("id", applicationId).param("kind", kind.name()).param("token", token).param("hash", contentHash)
                .param("req", Times.utc(requestedAt)).param("ok", approvedAt == null ? null : Times.utc(approvedAt))
                .update();
    }

    // ───────────── 마무리 서류 ─────────────

    record CloseRow(long applicationId, OffsetDateTime report, OffsetDateTime credit, OffsetDateTime survey,
                    OffsetDateTime evaluation, OffsetDateTime attendance, OffsetDateTime remindedAt) {

        static CloseRow empty(long id) {
            return new CloseRow(id, null, null, null, null, null, null);
        }
    }

    Map<Long, CloseRow> closes(List<Long> ids) {
        Map<Long, CloseRow> out = new LinkedHashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        db.sql("SELECT * FROM internship_close WHERE application_id IN (:ids)")
                .param("ids", ids)
                .query((ResultSet rs) -> {
                    out.put(rs.getLong("application_id"), new CloseRow(rs.getLong("application_id"),
                            time(rs, "report_submitted_at"), time(rs, "credit_submitted_at"),
                            time(rs, "survey_submitted_at"), time(rs, "evaluation_received_at"),
                            time(rs, "attendance_received_at"), time(rs, "reminded_at")));
                });
        return out;
    }

    /** 마무리 서류 칸 하나를 지금 시각(또는 null)으로. column은 이 클래스가 정한 이름만 받는다. */
    void setClose(long applicationId, String column, Instant at) {
        if (!List.of("report_submitted_at", "credit_submitted_at", "survey_submitted_at", "evaluation_received_at",
                "attendance_received_at", "reminded_at").contains(column)) {
            throw new IllegalArgumentException(column);
        }
        db.sql("INSERT INTO internship_close (application_id, " + column + ") VALUES (:id, :at)"
                        + " ON CONFLICT (application_id) DO UPDATE SET " + column + " = EXCLUDED." + column)
                .param("id", applicationId).param("at", at == null ? null : Times.utc(at)).update();
    }

    // ───────────── 체험 데이터 ─────────────

    /** 가상 지원자·체험 학생의 지난 기록을 한 번에 넣는다(낸 뒤 상태). */
    long insertDemo(int roundId, Long userId, UUID group, Status status, String receiptNo, Applicant a, int departmentId,
                    int grade, int semesters, BigDecimal gpa, boolean graduationExpected, Resume resume,
                    List<String> essays, String signature, int counsel, String fixReason, Instant submittedAt,
                    Instant receivedAt) {
        return db.sql("""
                        INSERT INTO application (round_id, user_id, demo_group_id, is_virtual, status, receipt_no, name_ko,
                               name_en, birth_date, gender, phone, email, address, student_no, minor_major, department_id,
                               grade, completed_semesters, gpa, graduation_expected, resume, essays, pledge,
                               consent_collect, consent_third_party, signature, counsel_count, fix_reason, created_at,
                               updated_at, submitted_at, received_at)
                        VALUES (:round, :user, :group, true, :status, :receipt, :nameKo, :nameEn, :birth, :gender, :phone,
                                :email, :address, :studentNo, :minor, :dep, :grade, :sem, :gpa, :grad,
                                CAST(:resume AS jsonb), :essays, true, true, true, :signature, :counsel, :fix,
                                :submitted, :submitted, :submitted, :received)
                        RETURNING id""")
                .param("round", roundId).param("user", userId).param("group", group).param("status", status.name())
                .param("receipt", receiptNo).param("nameKo", a.nameKo()).param("nameEn", a.nameEn())
                .param("birth", a.birthDate()).param("gender", a.gender()).param("phone", a.phone())
                .param("email", a.email()).param("address", a.address()).param("studentNo", a.studentNo())
                .param("minor", a.minorMajor()).param("dep", departmentId).param("grade", grade).param("sem", semesters)
                .param("gpa", gpa).param("grad", graduationExpected).param("resume", JSON.writeValueAsString(resume))
                .param("essays", essays.toArray(String[]::new)).param("signature", signature).param("counsel", counsel)
                .param("fix", fixReason).param("submitted", Times.utc(submittedAt))
                .param("received", receivedAt == null ? null : Times.utc(receivedAt))
                .query(Long.class).single();
    }

    /** 체험 데이터용: 매칭·면접·결과·알림을 한 번에. */
    void setPlacementDirect(long id, int rank, Instant matchedAt, OffsetDateTime interviewAt, InterviewMode mode,
                            Result result, Instant notifiedAt) {
        db.sql("""
                        UPDATE application SET status = 'MATCHED', matched_rank = :rank, matched_at = :matched,
                               interview_at = :at, interview_mode = :mode, result = :result, result_notified_at = :notified
                        WHERE id = :id""")
                .param("rank", rank).param("matched", Times.utc(matchedAt)).param("at", interviewAt)
                .param("mode", mode == null ? null : mode.name()).param("result", result.name())
                .param("notified", notifiedAt == null ? null : Times.utc(notifiedAt)).param("id", id).update();
    }

    /**
     * 묶음이 있으면 만료를 늦추고(같은 브라우저의 다음 체험 계정), 없으면 새로 만든다. 새로 만들었으면 true.
     * 만료됐지만 정리 작업이 아직 지우지 않은 묶음은 먼저 지운다(묶음 만료 = 구성원 만료의 최댓값이라 지워지는 것은 이미 만료된
     * 체험 계정·가상 지원자뿐이다).
     */
    boolean upsertGroup(UUID id, Instant expiresAt, Instant now) {
        db.sql("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtext(:key))) l").param("key", "demo:" + id)
                .query(Integer.class).single();
        db.sql("DELETE FROM demo_group WHERE id = :id AND expires_at <= :now")
                .param("id", id).param("now", Times.utc(now)).update();
        int touched = db.sql("UPDATE demo_group SET expires_at = greatest(expires_at, :exp) WHERE id = :id")
                .param("exp", Times.utc(expiresAt)).param("id", id).update();
        if (touched > 0) {
            return false;
        }
        db.sql("INSERT INTO demo_group (id, expires_at) VALUES (:id, :exp)")
                .param("id", id).param("exp", Times.utc(expiresAt)).update();
        return true;
    }

    void setUserDemo(long userId, UUID group, LocalDate today) {
        db.sql("UPDATE app_user SET demo_group_id = :group, demo_today = :today WHERE id = :id")
                .param("group", group).param("today", today).param("id", userId).update();
    }

    void insertPlanItem(long userId, int jobId, Integer rank, Instant addedAt) {
        db.sql("INSERT INTO plan_item (user_id, job_id, rank, added_at) VALUES (:user, :job, :rank, :at) ON CONFLICT DO NOTHING")
                .param("user", userId).param("job", jobId).param("rank", rank).param("at", Times.utc(addedAt)).update();
    }

    /** 체험 데이터용: 받은 시각만 정해 접수 완료로. */
    void receiveAt(long id, Instant at) {
        db.sql("UPDATE application SET status = 'RECEIVED', received_at = :at WHERE id = :id")
                .param("at", Times.utc(at)).param("id", id).update();
    }

    Optional<Integer> departmentId(String name) {
        return db.sql("SELECT id FROM department WHERE name = :name").param("name", name).query(Integer.class).optional();
    }

    /** 시드에 있는 직무만 남긴다(체험 템플릿의 직무가 시드에서 빠져도 체험 계정은 만들어지게). */
    List<Integer> existingJobs(List<Integer> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        List<Integer> have = db.sql("SELECT id FROM job WHERE id IN (:ids)").param("ids", ids).query(Integer.class).list();
        return ids.stream().filter(have::contains).toList();
    }

    // ───────────── 도우미 ─────────────

    static OffsetDateTime time(ResultSet rs, String column) throws SQLException {
        return Times.kst(rs.getObject(column, OffsetDateTime.class));
    }

    static List<String> strings(Array array) throws SQLException {
        return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
    }
}
