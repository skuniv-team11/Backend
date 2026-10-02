package kr.ac.skuniv.coopradar.commute;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 통근 조회의 출발점(area 시·군·구)과 도착점(근로지 주소). 좌표는 DB에 없다 — 조회할 때마다 카카오 주소 검색으로 구하고 버린다(ADR-0007).
 */
@Repository
public class CommuteRepository {

    private final JdbcClient db;

    public CommuteRepository(JdbcClient db) {
        this.db = db;
    }

    /** 사는 곳(시·군·구). sido는 '서울'·'인천'·'경기'. */
    public record Area(String code, String sido, String name) {

        /** 카카오 주소 검색어(예: '서울 노원구', '경기 수원시 장안구'). */
        String query() {
            return sido + " " + name;
        }
    }

    /** 직무의 근로지 주소. 근로지 행이 없으면 기관 주소. 둘 다 없으면 null. */
    public record JobPlace(long jobId, String address) {
    }

    public Optional<Area> findArea(String code) {
        return db.sql("SELECT code, sido, name FROM area WHERE code = :code")
                .param("code", code)
                .query((rs, i) -> new Area(rs.getString("code"), rs.getString("sido"), rs.getString("name")))
                .optional();
    }

    public Optional<JobPlace> findJobPlace(long jobId) {
        return db.sql("""
                        SELECT j.id, COALESCE(w.address, i.address) AS address
                        FROM job j
                        JOIN institution i ON i.id = j.institution_id
                        LEFT JOIN workplace w ON w.id = j.workplace_id
                        WHERE j.id = :id""")
                .param("id", jobId)
                .query((rs, i) -> new JobPlace(rs.getLong("id"), rs.getString("address")))
                .optional();
    }
}
