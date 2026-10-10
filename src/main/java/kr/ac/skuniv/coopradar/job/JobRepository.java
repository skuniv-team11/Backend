package kr.ac.skuniv.coopradar.job;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kr.ac.skuniv.coopradar.job.JobDetail.Closing;
import kr.ac.skuniv.coopradar.job.JobDetail.Conditions;
import kr.ac.skuniv.coopradar.job.JobDetail.DepartmentRef;
import kr.ac.skuniv.coopradar.job.JobDetail.Evidence;
import kr.ac.skuniv.coopradar.job.JobDetail.Institution;
import kr.ac.skuniv.coopradar.job.JobDetail.MajorAlias;
import kr.ac.skuniv.coopradar.job.JobDetail.Period;
import kr.ac.skuniv.coopradar.job.JobDetail.Photo;
import kr.ac.skuniv.coopradar.job.JobDetail.Requirements;
import kr.ac.skuniv.coopradar.job.JobDetail.SeniorNote;
import kr.ac.skuniv.coopradar.job.JobDetail.WeeklyPlan;
import kr.ac.skuniv.coopradar.job.JobDetail.Workplace;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 직무 상세에 쓰는 시드 읽기(job·institution·workplace·근거·검토 알림·수기·소개서 사진). 시드 테이블은 읽기만 한다(V1 원칙). */
@Repository
public class JobRepository {

    private final JdbcClient db;

    public JobRepository(JdbcClient db) {
        this.db = db;
    }

    /** 직무 1행과 붙는 회차·기관·근로지. 목록 칸(주차 계획·근거 등)은 비워 두고 서비스가 채운다. */
    record JobRow(int id, RoundRef round, Institution institution, String team, String title, String overview,
                  String educationGoal, String competencies, Conditions conditions, Requirements requirements,
                  Workplace workplace, Closing closing) {
    }

    Optional<JobRow> findJob(long jobId) {
        return db.sql("""
                        SELECT j.*, r.term_code,
                               i.name AS i_name, i.size AS i_size, i.listing AS i_listing,
                               i.business_type AS i_business_type, i.business_item AS i_business_item,
                               i.address AS i_address, i.nts_status AS i_nts_status, i.nts_checked_on AS i_nts_checked_on,
                               w.address AS w_address, (w.address IS NOT NULL) AS w_has_coordinates
                        FROM job j
                        JOIN recruit_round r ON r.id = j.round_id
                        JOIN institution i ON i.id = j.institution_id
                        LEFT JOIN workplace w ON w.id = j.workplace_id
                        WHERE j.id = :id""")
                .param("id", jobId)
                .query((rs, n) -> new JobRow(
                        rs.getInt("id"),
                        new RoundRef(rs.getInt("round_id"), rs.getString("term_code")),
                        new Institution(rs.getInt("institution_id"), rs.getString("i_name"),
                                InstitutionLogos.pathFor(rs.getInt("institution_id")), rs.getString("i_size"),
                                rs.getString("i_listing"), rs.getString("i_business_type"),
                                rs.getString("i_business_item"), rs.getString("i_address"),
                                rs.getString("i_nts_status"), rs.getObject("i_nts_checked_on", LocalDate.class)),
                        rs.getString("team"),
                        rs.getString("title"),
                        rs.getString("overview"),
                        rs.getString("education_goal"),
                        rs.getString("competencies"),
                        new Conditions(
                                rs.getString("course"),
                                rs.getString("job_type"),
                                new Period(rs.getObject("period_start", LocalDate.class),
                                        rs.getObject("period_end", LocalDate.class)),
                                rs.getString("work_hours_text"),
                                rs.getBigDecimal("weekly_hours"),
                                strings(rs, "weekdays"),
                                rs.getString("overtime"),
                                (Boolean) rs.getObject("labor_contract"),
                                Stipend.of(rs.getString("stipend_basis"), (Integer) rs.getObject("stipend_amount")),
                                strings(rs, "benefits"),
                                rs.getInt("headcount")),
                        new Requirements(
                                rs.getString("grade_rule"),
                                rs.getBigDecimal("gpa_min"),
                                rs.getString("portfolio"),
                                rs.getString("certificate"),
                                rs.getString("certificate_text"),
                                rs.getString("major_text"),
                                rs.getBoolean("major_open"),
                                List.of()),
                        rs.getString("w_address") == null ? null
                                : new Workplace(rs.getString("w_address"), rs.getBoolean("w_has_coordinates")),
                        new Closing(rs.getObject("closes_on", LocalDate.class), rs.getString("close_reason"),
                                rs.getBoolean("closes_on_is_virtual"))))
                .optional();
    }

    List<WeeklyPlan> weeklyPlan(int jobId) {
        return db.sql("SELECT seq, weeks_label, content FROM job_weekly_plan WHERE job_id = :id ORDER BY seq")
                .param("id", jobId)
                .query((rs, n) -> new WeeklyPlan(rs.getInt("seq"), rs.getString("weeks_label"), rs.getString("content")))
                .list();
    }

    /** 이 직무의 근거와 그 기관의 근거. 순서는 서비스가 {@link EvidenceLabels} 순서로 맞춘다. */
    List<Evidence> evidence(int jobId, int institutionId) {
        return db.sql("""
                        SELECT e.field_key, e.raw_value, e.page, e.quote, d.title
                        FROM field_evidence e
                        JOIN source_document d ON d.id = e.source_document_id
                        WHERE e.job_id = :job OR e.institution_id = :institution
                        ORDER BY e.id""")
                .param("job", jobId)
                .param("institution", institutionId)
                .query((rs, n) -> new Evidence(rs.getString("field_key"), EvidenceLabels.label(rs.getString("field_key")),
                        rs.getString("raw_value"), rs.getString("title"), rs.getInt("page"), rs.getString("quote")))
                .list();
    }

    /** 이 직무에 걸린 알림과 기관 전체에 걸린 알림(job_id NULL). */
    List<Alert> alerts(int jobId, int institutionId) {
        return db.sql("""
                        SELECT a.*, i.name AS i_name, d.title AS doc_title
                        FROM review_alert a
                        JOIN institution i ON i.id = a.institution_id
                        LEFT JOIN source_document d ON d.id = a.source_document_id
                        WHERE a.job_id = :job OR (a.job_id IS NULL AND a.institution_id = :institution)
                        ORDER BY a.id""")
                .param("job", jobId)
                .param("institution", institutionId)
                .query((rs, n) -> alert(rs))
                .list();
    }

    /** 직무의 선호 전공 표기와 확정된 학과(표기 id 순, 학과 id 순). */
    List<MajorAlias> majorAliases(int jobId) {
        Map<Integer, String> labels = new LinkedHashMap<>();
        Map<Integer, List<DepartmentRef>> departments = new LinkedHashMap<>();
        db.sql("""
                        SELECT m.id, m.label, d.id AS department_id, d.name AS department_name
                        FROM job_major_alias jma
                        JOIN major_alias m ON m.id = jma.alias_id
                        LEFT JOIN major_alias_department mad ON mad.alias_id = m.id
                        LEFT JOIN department d ON d.id = mad.department_id
                        WHERE jma.job_id = :job
                        ORDER BY m.id, d.id""")
                .param("job", jobId)
                .query(rs -> {
                    int aliasId = rs.getInt("id");
                    labels.putIfAbsent(aliasId, rs.getString("label"));
                    List<DepartmentRef> list = departments.computeIfAbsent(aliasId, k -> new ArrayList<>());
                    int departmentId = rs.getInt("department_id");
                    if (!rs.wasNull()) {
                        list.add(new DepartmentRef(departmentId, rs.getString("department_name")));
                    }
                });
        List<MajorAlias> out = new ArrayList<>();
        labels.forEach((id, label) -> out.add(new MajorAlias(label, List.copyOf(departments.get(id)))));
        return out;
    }

    /** 같은 기관의 선배 수기 전문. 최근 학기 먼저, 같은 학기는 쪽 순. */
    List<SeniorNote> seniorNotes(int institutionId) {
        return db.sql("""
                        SELECT d.term_code, t.team_text, d.title, t.page, t.major_text, t.grade_text, t.one_line,
                               t.company_intro, t.activities, t.outcomes, t.results, t.reflection
                        FROM testimonial t
                        JOIN source_document d ON d.id = t.source_document_id
                        WHERE t.institution_id = :institution
                        ORDER BY d.term_code DESC, t.page, t.id""")
                .param("institution", institutionId)
                .query((rs, n) -> new SeniorNote(rs.getString("term_code"), rs.getString("team_text"),
                        rs.getString("title"), rs.getInt("page"), rs.getString("major_text"), rs.getString("grade_text"),
                        rs.getString("one_line"), rs.getString("company_intro"), strings(rs, "activities"),
                        strings(rs, "outcomes"), rs.getString("results"), rs.getString("reflection")))
                .list();
    }

    /** 그 기관 소개서의 사진(순번 순). 파일은 static/photos/{기관 id}/{순번}.jpg(ADR-0030). */
    List<Photo> photos(int institutionId) {
        return db.sql("""
                        SELECT p.seq, p.caption, p.page, p.width, p.height, d.title
                        FROM institution_photo p
                        JOIN source_document d ON d.id = p.source_document_id
                        WHERE p.institution_id = :institution
                        ORDER BY p.seq""")
                .param("institution", institutionId)
                .query((rs, n) -> new Photo(rs.getInt("seq"), "/photos/" + institutionId + "/" + rs.getInt("seq") + ".jpg",
                        rs.getString("caption"), rs.getString("title"), rs.getInt("page"), rs.getInt("width"),
                        rs.getInt("height")))
                .list();
    }

    /** review_alert 행(+ i_name·doc_title 열) → Alert. 현황판도 쓴다. */
    public static Alert alert(ResultSet rs) throws SQLException {
        return new Alert(
                rs.getInt("id"),
                new InstitutionRef(rs.getInt("institution_id"), rs.getString("i_name")),
                (Integer) rs.getObject("job_id"),
                rs.getString("kind"),
                rs.getString("field_key"),
                rs.getString("description"),
                rs.getString("doc_title"),
                shortOrNull(rs, "page_a"),
                rs.getString("quote_a"),
                shortOrNull(rs, "page_b"),
                rs.getString("quote_b"));
    }

    private static Integer shortOrNull(ResultSet rs, String column) throws SQLException {
        int v = rs.getInt(column);
        return rs.wasNull() ? null : v;
    }

    private static List<String> strings(ResultSet rs, String column) throws SQLException {
        Array array = rs.getArray(column);
        if (array == null) {
            return List.of();
        }
        return Arrays.stream((Object[]) array.getArray()).map(String::valueOf).toList();
    }
}
