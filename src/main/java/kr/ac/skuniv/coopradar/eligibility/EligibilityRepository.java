package kr.ac.skuniv.coopradar.eligibility;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import kr.ac.skuniv.coopradar.eligibility.EligibilityDtos.ReasonCitation;
import kr.ac.skuniv.coopradar.eligibility.JobRequirement.AlertRef;
import kr.ac.skuniv.coopradar.job.InstitutionRef;
import kr.ac.skuniv.coopradar.job.JobDetail.Closing;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 판정에 쓰는 시드 읽기(job·선호 전공 매핑·검토 알림·학과·자격증·판정 출처). 시드 테이블은 읽기만 한다(V1 원칙). */
@Repository
public class EligibilityRepository {

    private final JdbcClient db;

    public EligibilityRepository(JdbcClient db) {
        this.db = db;
    }

    public Optional<String> departmentName(int departmentId) {
        return db.sql("SELECT name FROM department WHERE id = :id")
                .param("id", departmentId)
                .query(String.class)
                .optional();
    }

    /** 자격증 코드표(certificate)에 없는 코드. 프로필의 certificates 검사용(ADR-0021). */
    public List<String> unknownCertificates(Collection<String> codes) {
        if (codes.isEmpty()) {
            return List.of();
        }
        Set<String> known = new HashSet<>(db.sql("SELECT code FROM certificate WHERE code IN (:codes)")
                .param("codes", codes)
                .query(String.class)
                .list());
        return codes.stream().filter(c -> !known.contains(c)).distinct().toList();
    }

    /** 회차 직무 전부의 요건. 순서는 센터 참여기관 리스트 순번. */
    public List<JobRequirement> requirements(int roundId) {
        Map<Integer, Set<Integer>> majors = new HashMap<>();
        db.sql("""
                        SELECT jma.job_id, mad.department_id
                        FROM job_major_alias jma
                        JOIN major_alias_department mad ON mad.alias_id = jma.alias_id
                        JOIN job j ON j.id = jma.job_id
                        WHERE j.round_id = :round""")
                .param("round", roundId)
                .query(rs -> {
                    majors.computeIfAbsent(rs.getInt("job_id"), k -> new HashSet<>()).add(rs.getInt("department_id"));
                });

        // 직무에 걸린 알림 + 기관 전체(job_id NULL)에 걸린 알림. 판정 항목 필드(ADR-0016)만
        Map<Integer, List<AlertRef>> byJob = new HashMap<>();
        Map<Integer, List<AlertRef>> byInstitution = new HashMap<>();
        db.sql("""
                        SELECT id, institution_id, job_id, kind, field_key
                        FROM review_alert
                        WHERE field_key IN (:fields)
                        ORDER BY id""")
                .param("fields", EligibilityRules.JUDGED_FIELDS.keySet())
                .query(rs -> {
                    AlertRef ref = new AlertRef(rs.getInt("id"), rs.getString("kind"), rs.getString("field_key"));
                    Integer jobId = (Integer) rs.getObject("job_id");
                    if (jobId == null) {
                        byInstitution.computeIfAbsent(rs.getInt("institution_id"), k -> new ArrayList<>()).add(ref);
                    } else {
                        byJob.computeIfAbsent(jobId, k -> new ArrayList<>()).add(ref);
                    }
                });

        // 판정 이유 줄의 출처(ADR-0023): 직무별 항목 → 원문
        Map<Integer, Map<String, ReasonCitation>> sources = new HashMap<>();
        db.sql("""
                        SELECT s.job_id, s.item, s.source_type, s.document_title, s.page, s.quote
                        FROM requirement_source s
                        JOIN job j ON j.id = s.job_id
                        WHERE j.round_id = :round""")
                .param("round", roundId)
                .query(rs -> {
                    sources.computeIfAbsent(rs.getInt("job_id"), k -> new HashMap<>()).put(rs.getString("item"),
                            new ReasonCitation(rs.getString("source_type"), rs.getString("document_title"),
                                    rs.getObject("page", Integer.class), rs.getString("quote")));
                });

        return db.sql("""
                        SELECT j.id, j.list_seq, j.title, j.team, j.institution_id, i.name AS institution_name,
                               j.course, j.grade_rule, j.gpa_min, j.portfolio, j.certificate, j.certificate_text,
                               j.certificate_code,
                               j.major_text, j.major_open, j.closes_on, j.close_reason, j.closes_on_is_virtual,
                               (SELECT count(*) FROM review_alert a WHERE a.job_id = j.id) AS alert_count
                        FROM job j
                        JOIN institution i ON i.id = j.institution_id
                        WHERE j.round_id = :round
                        ORDER BY j.list_seq""")
                .param("round", roundId)
                .query((rs, n) -> {
                    int jobId = rs.getInt("id");
                    int institutionId = rs.getInt("institution_id");
                    List<AlertRef> alerts = new ArrayList<>(byInstitution.getOrDefault(institutionId, List.of()));
                    alerts.addAll(byJob.getOrDefault(jobId, List.of()));
                    alerts.sort((a, b) -> Integer.compare(a.id(), b.id()));
                    return new JobRequirement(jobId, rs.getInt("list_seq"), rs.getString("title"), rs.getString("team"),
                            new InstitutionRef(institutionId, rs.getString("institution_name")),
                            rs.getString("course"), rs.getString("grade_rule"), rs.getBigDecimal("gpa_min"),
                            rs.getString("portfolio"), rs.getString("certificate"), rs.getString("certificate_text"),
                            rs.getString("certificate_code"), Map.copyOf(sources.getOrDefault(jobId, Map.of())),
                            rs.getString("major_text"), rs.getBoolean("major_open"),
                            Set.copyOf(majors.getOrDefault(jobId, Set.of())), List.copyOf(alerts),
                            new Closing(rs.getObject("closes_on", LocalDate.class), rs.getString("close_reason"),
                                    rs.getBoolean("closes_on_is_virtual")),
                            rs.getInt("alert_count"));
                })
                .list();
    }
}
