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
                  String certificate, String certificateText, List<String> weekdays) {
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
                               END AS eligible_pool
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
                            List.copyOf(weekdays));
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
}
