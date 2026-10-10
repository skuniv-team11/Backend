package kr.ac.skuniv.coopradar.center;

import java.util.ArrayList;
import java.util.List;
import kr.ac.skuniv.coopradar.job.Alert;
import kr.ac.skuniv.coopradar.job.InstitutionRef;
import kr.ac.skuniv.coopradar.job.JobRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 현황판에 쓰는 시드 읽기(직무·적격 학생 풀·검토 알림). 시드 테이블은 읽기만 한다(V1 원칙). */
@Repository
public class CenterRepository {

    private final JdbcClient db;

    public CenterRepository(JdbcClient db) {
        this.db = db;
    }

    /**
     * 직무 1행. eligiblePool은 선호 전공 표기에서 사람이 확정한 학과(중복 없이)의 재학생 수 합,
     * 전공 무관이면 전체 재학생 수다. 확정 안 된 표기(DRAFT)는 0으로 센다.
     */
    record JobRow(int id, InstitutionRef institution, String title, int headcount, int eligiblePool, String portfolio,
                  String certificate, String certificateText, List<String> weekdays, int views) {
    }

    List<JobRow> jobs(int roundId) {
        return db.sql("""
                        SELECT j.id, j.institution_id, i.name AS institution_name, j.title, j.headcount,
                               j.portfolio, j.certificate, j.certificate_text, j.weekdays,
                               CASE WHEN j.major_open THEN (SELECT COALESCE(sum(enrolled_count), 0) FROM department)
                                    ELSE (SELECT COALESCE(sum(d.enrolled_count), 0)
                                          FROM department d
                                          WHERE d.id IN (SELECT mad.department_id
                                                         FROM job_major_alias jma
                                                         JOIN major_alias_department mad ON mad.alias_id = jma.alias_id
                                                         WHERE jma.job_id = j.id))
                               END AS eligible_pool,
                               (SELECT count(*) FROM job_view v WHERE v.job_id = j.id) AS views
                        FROM job j
                        JOIN institution i ON i.id = j.institution_id
                        WHERE j.round_id = :round
                        ORDER BY j.list_seq""")
                .param("round", roundId)
                .query((rs, n) -> {
                    var weekdays = new ArrayList<String>();
                    for (Object d : (Object[]) rs.getArray("weekdays").getArray()) {
                        weekdays.add(String.valueOf(d));
                    }
                    return new JobRow(rs.getInt("id"),
                            new InstitutionRef(rs.getInt("institution_id"), rs.getString("institution_name")),
                            rs.getString("title"), rs.getInt("headcount"), rs.getInt("eligible_pool"),
                            rs.getString("portfolio"), rs.getString("certificate"), rs.getString("certificate_text"),
                            List.copyOf(weekdays), rs.getInt("views"));
                })
                .list();
    }

    /** 회차 기관들에 걸린 알림 전부(직무 알림 + 기관 전체 알림), id 순. */
    List<Alert> alerts(int roundId) {
        return db.sql("""
                        SELECT a.*, i.name AS i_name, d.title AS doc_title
                        FROM review_alert a
                        JOIN institution i ON i.id = a.institution_id
                        LEFT JOIN source_document d ON d.id = a.source_document_id
                        WHERE a.institution_id IN (SELECT institution_id FROM job WHERE round_id = :round)
                          AND (a.job_id IS NULL OR a.job_id IN (SELECT id FROM job WHERE round_id = :round))
                        ORDER BY a.id""")
                .param("round", roundId)
                .query((rs, n) -> JobRepository.alert(rs))
                .list();
    }

    /** 이 센터 계정이 보는 범위(체험 센터는 자기 묶음, 가입 센터는 묶음 없는 것 — ADR-0033)를 SQL 조건으로. */
    private static final String ME = "WITH me AS (SELECT is_guest, demo_group_id FROM app_user WHERE id = :user) ";

    /** 처리할 것: 새로 들어온 지원서 · 보완 요청 중. */
    CenterDtos.Todo todo(long userId, int roundId) {
        return db.sql(ME + """
                        SELECT count(*) FILTER (WHERE a.status = 'SUBMITTED') AS submitted,
                               count(*) FILTER (WHERE a.status = 'FIX_REQUESTED') AS fix
                        FROM application a, me
                        WHERE a.round_id = :round
                          AND CASE WHEN me.is_guest THEN a.demo_group_id IS NOT DISTINCT FROM me.demo_group_id
                                        AND a.demo_group_id IS NOT NULL
                                   ELSE a.demo_group_id IS NULL END""")
                .param("user", userId).param("round", roundId)
                .query((rs, n) -> new CenterDtos.Todo(rs.getInt("submitted"), rs.getInt("fix"), null))
                .single();
    }

    /**
     * 학생이 찾는 직무: 범위 안 학생의 직무 탐색 1~3위 직무를 NCS 세분류로 묶어 학생 수를 센다(한 학생은 세분류마다 한 번).
     * 이번 회차 공고(직무 수·정원)와 나란히. 직무에 고른 세분류만.
     */
    CenterDtos.Demand demand(long userId, int roundId) {
        String users = ME + """
                , users AS (SELECT u.id FROM app_user u, me
                            WHERE CASE WHEN me.is_guest THEN u.demo_group_id = me.demo_group_id ELSE NOT u.is_guest END)
                , picked AS (SELECT DISTINCT r.user_id, n.subcategory_code FROM explore_run r
                             JOIN explore_item i ON i.run_id = r.id AND i.rank <= 3
                             JOIN job_ncs n ON n.job_id = i.job_id
                             WHERE r.round_id = :round AND r.user_id IN (SELECT id FROM users))
                """;
        int explorers = db.sql(users + "SELECT count(DISTINCT user_id) FROM picked")
                .param("user", userId).param("round", roundId).query(Integer.class).single();
        List<CenterDtos.DemandRow> rows = db.sql(users + """
                        SELECT s.code, s.name,
                               (SELECT count(*) FROM picked p WHERE p.subcategory_code = s.code) AS students,
                               count(j.id) AS jobs, coalesce(sum(j.headcount), 0) AS seats
                        FROM ncs_subcategory s
                        JOIN job_ncs n ON n.subcategory_code = s.code
                        JOIN job j ON j.id = n.job_id AND j.round_id = :round
                        GROUP BY s.code, s.name
                        ORDER BY students DESC, seats, s.code""")
                .param("user", userId).param("round", roundId)
                .query((rs, n) -> new CenterDtos.DemandRow(rs.getString("code"), rs.getString("name"),
                        rs.getInt("students"), rs.getInt("jobs"), rs.getInt("seats")))
                .list();
        return new CenterDtos.Demand(explorers, rows);
    }
}
