package kr.ac.skuniv.coopradar.commute;

import java.math.BigDecimal;
import java.util.Optional;
import kr.ac.skuniv.coopradar.commute.CommuteProperties.Point;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 통근 조회의 출발점(area)과 도착점(workplace) 좌표. 둘 다 시드(공공 주소 API 값)이고 읽기만 한다. */
@Repository
public class CommuteRepository {

    private final JdbcClient db;

    public CommuteRepository(JdbcClient db) {
        this.db = db;
    }

    /** 사는 곳 대표점(시·군·구청). */
    public record Area(String code, String name, Point point) {
    }

    /** 직무의 근로지. 좌표가 없으면 point는 null. 근로지 행이 없으면 기관 주소를 보여 준다. */
    public record JobPlace(long jobId, String address, Point point) {
    }

    public Optional<Area> findArea(String code) {
        return db.sql("SELECT code, name, lat, lng FROM area WHERE code = :code")
                .param("code", code)
                .query((rs, i) -> new Area(rs.getString("code"), rs.getString("name"),
                        new Point(rs.getBigDecimal("lat"), rs.getBigDecimal("lng"))))
                .optional();
    }

    public Optional<JobPlace> findJobPlace(long jobId) {
        return db.sql("""
                        SELECT j.id, COALESCE(w.address, i.address) AS address, w.lat, w.lng
                        FROM job j
                        JOIN institution i ON i.id = j.institution_id
                        LEFT JOIN workplace w ON w.id = j.workplace_id
                        WHERE j.id = :id""")
                .param("id", jobId)
                .query((rs, i) -> {
                    BigDecimal lat = rs.getBigDecimal("lat");
                    BigDecimal lng = rs.getBigDecimal("lng");
                    return new JobPlace(rs.getLong("id"), rs.getString("address"),
                            lat == null || lng == null ? null : new Point(lat, lng));
                })
                .optional();
    }
}
