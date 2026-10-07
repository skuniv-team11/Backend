package kr.ac.skuniv.coopradar.recommend;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import kr.ac.skuniv.coopradar.job.Stipend;
import kr.ac.skuniv.coopradar.recommend.EvidencePicker.Testimonial;
import kr.ac.skuniv.coopradar.recommend.EvidenceText.JobText;
import kr.ac.skuniv.coopradar.recommend.FitScorer.Features;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 추천에 쓰는 시드 읽기(직무 텍스트·유형·지원비, 인용 재료·수기·선호 전공 표기). 시드 테이블은 읽기만 한다(V1 원칙). */
@Repository
public class RecommendRepository {

    private final JdbcClient db;

    public RecommendRepository(JdbcClient db) {
        this.db = db;
    }

    /**
     * 회차 직무별 점수 재료. 키워드 유사도 텍스트 = 부서 · 직무명 · 직무 개요 · 교육 목표 · 요구 역량 · 주차 계획 · 기관 업태·종목.
     * 선호 전공 원문은 넣지 않는다(전공은 규칙이 따로 본다, E5와 같은 이유). directDepartments = 학과를 콕 집은 선호 전공 표기
     * (가리키는 학과가 {@link FitScorer#DIRECT_MAX_DEPARTMENTS}개 이하)에 든 학과(ADR-0026).
     */
    Map<Integer, Features> features(int roundId) {
        Map<Integer, Features> out = new HashMap<>();
        db.sql("""
                        SELECT j.id, j.job_type, j.stipend_basis, j.stipend_amount,
                               concat_ws(E'\\n', j.team, j.title, j.overview, j.education_goal, j.competencies,
                                         (SELECT string_agg(p.content, E'\\n' ORDER BY p.seq)
                                          FROM job_weekly_plan p WHERE p.job_id = j.id),
                                         i.business_type, i.business_item) AS text,
                               (SELECT array_agg(DISTINCT mad.department_id)
                                FROM job_major_alias jma
                                JOIN major_alias_department mad ON mad.alias_id = jma.alias_id
                                WHERE jma.job_id = j.id
                                  AND (SELECT count(*) FROM major_alias_department m2 WHERE m2.alias_id = jma.alias_id)
                                      <= :direct) AS direct
                        FROM job j
                        JOIN institution i ON i.id = j.institution_id
                        WHERE j.round_id = :round""")
                .param("round", roundId)
                .param("direct", FitScorer.DIRECT_MAX_DEPARTMENTS)
                .query(rs -> {
                    out.put(rs.getInt("id"), new Features(rs.getString("text"), rs.getString("job_type"),
                            Stipend.of(rs.getString("stipend_basis"), (Integer) rs.getObject("stipend_amount")),
                            Set.copyOf(integers(rs.getArray("direct")))));
                });
        return out;
    }

    /**
     * 회차 직무 원문과 칸별 근거 쪽(인용 조각을 만드는 재료, ADR-0020). 주차 계획은 seq 순. 계획서 문서명은 그 직무 근거의 문서.
     */
    List<JobText> jobTexts(int roundId) {
        return db.sql("""
                        SELECT j.id, j.institution_id, j.team, j.title, j.overview, j.competencies, j.education_goal,
                               (SELECT array_agg(p.content ORDER BY p.seq) FROM job_weekly_plan p WHERE p.job_id = j.id) AS weekly,
                               (SELECT e.page FROM field_evidence e WHERE e.job_id = j.id AND e.field_key = 'jobOverview') AS p_overview,
                               (SELECT e.page FROM field_evidence e WHERE e.job_id = j.id AND e.field_key = 'competencies') AS p_comp,
                               (SELECT e.page FROM field_evidence e WHERE e.job_id = j.id AND e.field_key = 'educationGoal') AS p_goal,
                               (SELECT e.page FROM field_evidence e WHERE e.job_id = j.id AND e.field_key = 'majorRequirement') AS p_major,
                               (SELECT d.title FROM field_evidence e JOIN source_document d ON d.id = e.source_document_id
                                WHERE e.job_id = j.id ORDER BY e.id LIMIT 1) AS plan_title
                        FROM job j
                        WHERE j.round_id = :round
                        ORDER BY j.list_seq""")
                .param("round", roundId)
                .query((rs, n) -> new JobText(rs.getInt("id"), rs.getInt("institution_id"), rs.getString("team"),
                        rs.getString("title"), rs.getString("overview"), rs.getString("competencies"),
                        rs.getString("education_goal"), strings(rs.getArray("weekly")), page(rs, "p_overview"),
                        page(rs, "p_comp"), page(rs, "p_goal"), page(rs, "p_major"), rs.getString("plan_title")))
                .list();
    }

    /** 회차 기관의 선배 수기(기관별로 최근 학기 먼저). 실습 내용만 — 실습 결과·소감은 인용에 쓰지 않는다. */
    Map<Integer, List<Testimonial>> testimonials(int roundId) {
        Map<Integer, List<Testimonial>> out = new HashMap<>();
        db.sql("""
                        SELECT t.institution_id, d.title, d.term_code, t.team_text, t.page, t.activities
                        FROM testimonial t
                        JOIN source_document d ON d.id = t.source_document_id
                        WHERE t.institution_id IN (SELECT institution_id FROM job WHERE round_id = :round)
                        ORDER BY t.institution_id, d.term_code DESC, t.page, t.id""")
                .param("round", roundId)
                .query(rs -> {
                    Testimonial t = new Testimonial(rs.getInt("institution_id"), rs.getString("title"),
                            rs.getString("term_code"), rs.getString("team_text"), rs.getInt("page"),
                            strings(rs.getArray("activities")));
                    out.computeIfAbsent(t.institutionId(), k -> new ArrayList<>()).add(t);
                });
        return out;
    }

    /**
     * 직무별로, 이 학과가 들어 있는 선호 전공 표기(예: 메이크업디자인학과 → '미용예술대학'). 여러 개면 가장 좁은 표기(가리키는
     * 학과가 적은 것 — 학과를 콕 집은 표기가 계열보다 먼저, ADR-0026), 같으면 표기 id가 작은 것.
     */
    Map<Integer, String> majorLabels(int roundId, int departmentId) {
        Map<Integer, String> out = new HashMap<>();
        db.sql("""
                        SELECT jma.job_id, ma.label
                        FROM job_major_alias jma
                        JOIN job j ON j.id = jma.job_id
                        JOIN major_alias ma ON ma.id = jma.alias_id
                        JOIN major_alias_department mad ON mad.alias_id = jma.alias_id
                        WHERE j.round_id = :round AND mad.department_id = :department
                        ORDER BY jma.job_id,
                                 (SELECT count(*) FROM major_alias_department m2 WHERE m2.alias_id = ma.id), ma.id""")
                .param("round", roundId)
                .param("department", departmentId)
                .query(rs -> {
                    out.putIfAbsent(rs.getInt("job_id"), rs.getString("label"));
                });
        return out;
    }

    /** 자격증 코드 → 이름(코드표). 이유 문장이 '미용 자격증·면허증이 있어야 하는데'처럼 쓴다(ADR-0024). */
    Map<String, String> certificateLabels() {
        Map<String, String> out = new HashMap<>();
        db.sql("SELECT code, label FROM certificate").query(rs -> {
            out.put(rs.getString("code"), rs.getString("label"));
        });
        return out;
    }

    /** 이유 문장 프롬프트에 넣는 직무 사실. 원문 그대로(검증에서 인용 대조에도 쓴다). */
    record JobFacts(String institution, String businessType, String businessItem, String title, String team,
                    String jobType, String overview, String competencies, String educationGoal, String weeklyPlan,
                    String majorText) {
    }

    Optional<JobFacts> facts(int jobId) {
        return db.sql("""
                        SELECT i.name AS institution, i.business_type, i.business_item, j.title, j.team, j.job_type,
                               j.overview, j.competencies, j.education_goal, j.major_text,
                               (SELECT string_agg(p.content, ' / ' ORDER BY p.seq) FROM job_weekly_plan p
                                WHERE p.job_id = j.id) AS weekly
                        FROM job j JOIN institution i ON i.id = j.institution_id
                        WHERE j.id = :job""")
                .param("job", jobId)
                .query((rs, n) -> new JobFacts(rs.getString("institution"), rs.getString("business_type"),
                        rs.getString("business_item"), rs.getString("title"), rs.getString("team"),
                        rs.getString("job_type"), rs.getString("overview"), rs.getString("competencies"),
                        rs.getString("education_goal"), rs.getString("weekly"), rs.getString("major_text")))
                .optional();
    }

    private static Integer page(ResultSet rs, String column) throws SQLException {
        int v = rs.getInt(column);
        return rs.wasNull() ? null : v;
    }

    private static List<Integer> integers(Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        return Arrays.stream((Object[]) array.getArray()).map(x -> ((Number) x).intValue()).toList();
    }

    private static List<String> strings(Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        return Arrays.stream((Object[]) array.getArray()).map(String::valueOf).toList();
    }
}
