package kr.ac.skuniv.coopradar.reference;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import kr.ac.skuniv.coopradar.reference.ReferenceDtos.Area;
import kr.ac.skuniv.coopradar.reference.ReferenceDtos.Certificate;
import kr.ac.skuniv.coopradar.reference.ReferenceDtos.Department;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 기준 정보 시드(department·area·certificate·recruit_round·program) 읽기. 시드 테이블은 읽기만 한다(V1 원칙). */
@Repository
public class ReferenceRepository {

    private final JdbcClient db;

    public ReferenceRepository(JdbcClient db) {
        this.db = db;
    }

    /** 회차 1행. 리플레이 범위를 만들 수 있게 모집 시작·종료일이 둘 다 있는 회차만 담는다. */
    record RoundRow(int id, String programName, String termCode, int roundNo, LocalDate recruitStart, LocalDate recruitEnd) {
    }

    /** 순서는 시드가 정한 id 순(학과 시드의 원본 순서를 따른다). */
    public List<Department> departments() {
        return db.sql("SELECT id, name, college FROM department ORDER BY id")
                .query((rs, i) -> new Department(rs.getInt("id"), rs.getString("name"), rs.getString("college")))
                .list();
    }

    /** 순서는 시드의 sort_order(서울 → 인천 → 경기, 시드가 정함). */
    public List<Area> areas() {
        return db.sql("SELECT code, sido, name FROM area ORDER BY sort_order")
                .query((rs, i) -> new Area(rs.getString("code"), rs.getString("sido"), rs.getString("name")))
                .list();
    }

    /** 그 회차 직무가 요구·우대하는 자격증만, 코드표 순서(ADR-0021 — 판정에 쓰지 않는 자격증은 묻지 않는다). */
    public List<Certificate> certificates(int roundId) {
        return db.sql("""
                        SELECT c.code, c.label FROM certificate c
                        WHERE c.code IN (SELECT j.certificate_code FROM job j WHERE j.round_id = :round)
                        ORDER BY c.sort_order, c.code""")
                .param("round", roundId)
                .query((rs, i) -> new Certificate(rs.getString("code"), rs.getString("label")))
                .list();
    }

    /**
     * 현재 회차 = 모집 시작·종료일이 있는 회차 중 가장 최근(학기 → 회차 번호 순).
     * 지난 회차(이력용)는 학기가 더 이르므로 고르지 않는다.
     */
    Optional<RoundRow> latestRound() {
        return db.sql("""
                        SELECT r.id, p.name AS program_name, r.term_code, r.round_no, r.recruit_start, r.recruit_end
                        FROM recruit_round r
                        JOIN program p ON p.id = r.program_id
                        WHERE r.recruit_start IS NOT NULL AND r.recruit_end IS NOT NULL
                        ORDER BY r.term_code DESC, r.round_no DESC, r.id DESC
                        LIMIT 1""")
                .query((rs, i) -> new RoundRow(rs.getInt("id"), rs.getString("program_name"), rs.getString("term_code"),
                        rs.getInt("round_no"), rs.getObject("recruit_start", LocalDate.class),
                        rs.getObject("recruit_end", LocalDate.class)))
                .optional();
    }

    /** 회차 일정(seq 순). */
    List<ReferenceDtos.StageInfo> stages(int roundId) {
        return db.sql("""
                        SELECT code, phase, starts_on, ends_on, confirmed, source FROM round_stage
                        WHERE round_id = :round ORDER BY seq""")
                .param("round", roundId)
                .query((rs, i) -> new ReferenceDtos.StageInfo(ReferenceDtos.Stage.valueOf(rs.getString("code")),
                        ReferenceDtos.Phase.valueOf(rs.getString("phase")), rs.getObject("starts_on", LocalDate.class),
                        rs.getObject("ends_on", LocalDate.class), rs.getBoolean("confirmed"), rs.getString("source")))
                .list();
    }
}
