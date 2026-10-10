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

    /** 내 학과와 같은 묶음(department_cluster)에 든 다른 학과 — 가까운 학과(ADR-0028). */
    public Set<Integer> nearDepartments(int departmentId) {
        return new HashSet<>(db.sql("""
                        SELECT DISTINCT other.department_id
                        FROM department_cluster_member mine
                        JOIN department_cluster_member other ON other.cluster_id = mine.cluster_id
                        WHERE mine.department_id = :id AND other.department_id <> :id""")
                .param("id", departmentId)
                .query(Integer.class)
                .list());
    }

    /**
     * 회차 직무별로 학과를 콕 집어 적은 선호 전공 표기(가리키는 학과가 {@link EligibilityRules#NAMED_MAX_DEPARTMENTS}개 이하)의
     * 학과. 가까운 전공은 이 학과와만 따진다 — 계열·단과대 표기는 회사가 범위를 직접 정한 것이라서(ADR-0028).
     */
    public Map<Integer, Set<Integer>> namedDepartments(int roundId) {
        Map<Integer, Set<Integer>> out = new HashMap<>();
        db.sql("""
                        SELECT jma.job_id, mad.department_id
                        FROM job_major_alias jma
                        JOIN job j ON j.id = jma.job_id
                        JOIN major_alias_department mad ON mad.alias_id = jma.alias_id
                        WHERE j.round_id = :round
                          AND (SELECT count(*) FROM major_alias_department m2 WHERE m2.alias_id = jma.alias_id) <= :named""")
                .param("round", roundId)
                .param("named", EligibilityRules.NAMED_MAX_DEPARTMENTS)
                .query(rs -> {
                    out.computeIfAbsent(rs.getInt("job_id"), k -> new HashSet<>()).add(rs.getInt("department_id"));
                });
        return out;
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

        // 직무에 걸린 알림 + 기관 전체(job_id NULL)에 걸린 알림. 판정 항목 필드(ADR-0016)만, 이 회차 것만
        // (현황판 CenterRepository.alerts와 같은 조건 — 같은 기관이 다음 회차에 또 나와도 지난 알림이 판정을 바꾸지 않게)
        Map<Integer, List<AlertRef>> byJob = new HashMap<>();
        Map<Integer, List<AlertRef>> byInstitution = new HashMap<>();
        db.sql("""
                        SELECT id, institution_id, job_id, kind, field_key
                        FROM review_alert
                        WHERE field_key IN (:fields)
                          AND institution_id IN (SELECT institution_id FROM job WHERE round_id = :round)
                          AND (job_id IS NULL OR job_id IN (SELECT id FROM job WHERE round_id = :round))
                        ORDER BY id""")
                .param("fields", EligibilityRules.JUDGED_FIELDS.keySet())
                .param("round", roundId)
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

    // ───────── #14 행에 함께 보이는 값(ADR-0035) ─────────

    /** 회차 직무의 NCS 세분류(job_ncs). */
    Map<Integer, EligibilityDtos.NcsRef> ncsByJob(int roundId) {
        Map<Integer, EligibilityDtos.NcsRef> out = new HashMap<>();
        db.sql("""
                        SELECT n.job_id, s.code, s.name FROM job_ncs n
                        JOIN ncs_subcategory s ON s.code = n.subcategory_code
                        JOIN job j ON j.id = n.job_id WHERE j.round_id = :round""")
                .param("round", roundId)
                .query(rs -> {
                    out.put(rs.getInt("job_id"), new EligibilityDtos.NcsRef(rs.getString("code"), rs.getString("name")));
                });
        return out;
    }

    /** 회차 직무의 지금까지 조회 수(#25의 views와 같은 값, 0이면 빠진다). */
    Map<Integer, Integer> viewsByJob(int roundId) {
        Map<Integer, Integer> out = new HashMap<>();
        db.sql("""
                        SELECT v.job_id, count(*) AS views FROM job_view v JOIN job j ON j.id = v.job_id
                        WHERE j.round_id = :round GROUP BY v.job_id""")
                .param("round", roundId)
                .query(rs -> {
                    out.put(rs.getInt("job_id"), rs.getInt("views"));
                });
        return out;
    }

    record Planned(int jobId, Integer rank) {
    }

    /** 계정이 담은 직무와 순위. */
    List<Planned> plan(long userId) {
        return db.sql("SELECT job_id, rank FROM plan_item WHERE user_id = :user")
                .param("user", userId)
                .query((rs, n) -> new Planned(rs.getInt("job_id"), (Integer) rs.getObject("rank")))
                .list();
    }
}
