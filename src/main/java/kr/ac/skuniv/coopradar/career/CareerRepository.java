package kr.ac.skuniv.coopradar.career;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kr.ac.skuniv.coopradar.career.CareerDtos.Expand;
import kr.ac.skuniv.coopradar.career.CareerDtos.Occupation;
import kr.ac.skuniv.coopradar.career.CareerDtos.Origin;
import kr.ac.skuniv.coopradar.career.CareerDtos.Relation;
import kr.ac.skuniv.coopradar.career.CareerDtos.Source;
import kr.ac.skuniv.coopradar.career.CareerDtos.Unit;
import kr.ac.skuniv.coopradar.common.Times;
import kr.ac.skuniv.coopradar.explore.ExploreDtos.Fallback;
import kr.ac.skuniv.coopradar.job.InstitutionRef;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** NCS 시드 읽기와 커리어 리포트 저장(ADR-0032). 시드 테이블은 읽기만 한다. 실습 내용은 로그에 남기지 않는다. */
@Repository
public class CareerRepository {

    private final JdbcClient db;

    public CareerRepository(JdbcClient db) {
        this.db = db;
    }

    /** 직무 기본(제목·부서·기관)과 고른 NCS 세분류. 세분류가 없으면 subcategory가 null. */
    record JobNcs(int jobId, String title, String team, InstitutionRef institution, Subcategory subcategory, String note) {
    }

    record Subcategory(String code, String name, List<String> path) {
    }

    Optional<JobNcs> job(int jobId) {
        return db.sql("""
                        SELECT j.id, j.title, j.team, j.institution_id, i.name AS institution, n.note,
                               s.code, s.name, s.large_name, s.middle_name, s.small_name
                        FROM job j
                        JOIN institution i ON i.id = j.institution_id
                        LEFT JOIN job_ncs n ON n.job_id = j.id
                        LEFT JOIN ncs_subcategory s ON s.code = n.subcategory_code
                        WHERE j.id = :job""")
                .param("job", jobId)
                .query((rs, n) -> new JobNcs(rs.getInt("id"), rs.getString("title"), rs.getString("team"),
                        new InstitutionRef(rs.getInt("institution_id"), rs.getString("institution")),
                        rs.getString("code") == null ? null : new Subcategory(rs.getString("code"), rs.getString("name"),
                                List.of(rs.getString("large_name"), rs.getString("middle_name"), rs.getString("small_name"))),
                        rs.getString("note")))
                .optional();
    }

    Optional<Subcategory> subcategory(String code) {
        return db.sql("SELECT code, name, large_name, middle_name, small_name FROM ncs_subcategory WHERE code = :code")
                .param("code", code)
                .query((rs, n) -> new Subcategory(rs.getString("code"), rs.getString("name"),
                        List.of(rs.getString("large_name"), rs.getString("middle_name"), rs.getString("small_name"))))
                .optional();
    }

    /** 세분류의 능력단위(번호 순). */
    List<Unit> units(String subcategory) {
        return db.sql("SELECT code, name, level, definition FROM ncs_unit WHERE subcategory_code = :code ORDER BY seq, code")
                .param("code", subcategory)
                .query((rs, n) -> new Unit(rs.getString("code"), rs.getString("name"), (Integer) rs.getObject("level"),
                        rs.getString("definition")))
                .list();
    }

    /** 넓혀 갈 세분류(순위 순)와 능력단위 수·이름 앞 5개. */
    List<Expand> expand(String subcategory) {
        return db.sql("""
                        SELECT e.rank, e.relation, s.code, s.name, s.large_name, s.middle_name, s.small_name,
                               (SELECT count(*) FROM ncs_unit u WHERE u.subcategory_code = s.code) AS unit_count,
                               (SELECT array_agg(x.name ORDER BY x.seq, x.code) FROM (
                                   SELECT u.name, u.seq, u.code FROM ncs_unit u WHERE u.subcategory_code = s.code
                                   ORDER BY u.seq, u.code LIMIT 5) x) AS sample
                        FROM ncs_expand e
                        JOIN ncs_subcategory s ON s.code = e.to_code
                        WHERE e.from_code = :code
                        ORDER BY e.rank""")
                .param("code", subcategory)
                .query((rs, n) -> new Expand(rs.getInt("rank"), rs.getString("code"), rs.getString("name"),
                        List.of(rs.getString("large_name"), rs.getString("middle_name"), rs.getString("small_name")),
                        Relation.valueOf(rs.getString("relation")), rs.getInt("unit_count"),
                        rs.getArray("sample") == null ? List.of() : List.of((String[]) rs.getArray("sample").getArray())))
                .list();
    }

    /** 이어지는 직업(코드 순). */
    List<Occupation> occupations(String subcategory) {
        return db.sql("""
                        SELECT o.code, o.name, n.source FROM ncs_occupation n JOIN occupation o ON o.code = n.occupation_code
                        WHERE n.subcategory_code = :code ORDER BY o.code""")
                .param("code", subcategory)
                .query((rs, n) -> new Occupation(rs.getString("code"), rs.getString("name"),
                        Origin.valueOf(rs.getString("source"))))
                .list();
    }

    /** 저장된 커리어 리포트. */
    record Stored(long id, int jobId, String subcategory, String practiceText, Source source, Fallback fallback,
                  OffsetDateTime createdAt, Map<String, String[]> covered) {
    }

    Optional<Stored> find(long userId) {
        return db.sql("""
                        SELECT id, job_id, subcategory_code, practice_text, source, fallback_reason, created_at
                        FROM career_report WHERE user_id = :user""")
                .param("user", userId)
                .query((rs, n) -> new Stored(rs.getLong("id"), rs.getInt("job_id"), rs.getString("subcategory_code"),
                        rs.getString("practice_text"), Source.valueOf(rs.getString("source")),
                        rs.getString("fallback_reason") == null ? null : Fallback.valueOf(rs.getString("fallback_reason")),
                        Times.kst(rs.getObject("created_at", OffsetDateTime.class)), null))
                .optional()
                .map(s -> new Stored(s.id(), s.jobId(), s.subcategory(), s.practiceText(), s.source(), s.fallback(),
                        s.createdAt(), covered(s.id())));
    }

    /** 다룬 단위: 코드 → [학생 구절, 이유]. */
    private Map<String, String[]> covered(long reportId) {
        Map<String, String[]> out = new LinkedHashMap<>();
        db.sql("SELECT unit_code, student_quote, reason FROM career_report_unit WHERE report_id = :id ORDER BY unit_code")
                .param("id", reportId)
                .query(rs -> {
                    out.put(rs.getString("unit_code"), new String[] {rs.getString("student_quote"), rs.getString("reason")});
                });
        return out;
    }

    record Saved(long id, OffsetDateTime createdAt) {
    }

    /** 계정의 커리어 리포트를 이번 것으로 바꾼다(계정당 1건). */
    @Transactional
    public Saved replace(long userId, int jobId, String subcategory, String practiceText, Source source, Fallback fallback,
                         String model, String promptVersion, Map<String, String[]> covered) {
        db.sql("DELETE FROM career_report WHERE user_id = :user").param("user", userId).update();
        Saved saved = db.sql("""
                        INSERT INTO career_report (user_id, job_id, subcategory_code, practice_text, source, fallback_reason,
                                                   model, prompt_version)
                        VALUES (:user, :job, :sub, :text, :source, :fallback, :model, :pv)
                        RETURNING id, created_at""")
                .param("user", userId)
                .param("job", jobId)
                .param("sub", subcategory)
                .param("text", practiceText)
                .param("source", source.name())
                .param("fallback", fallback == null ? null : fallback.name())
                .param("model", model)
                .param("pv", promptVersion)
                .query((rs, n) -> new Saved(rs.getLong("id"), Times.kst(rs.getObject("created_at", OffsetDateTime.class))))
                .single();
        for (var e : new ArrayList<>(covered.entrySet())) {
            db.sql("""
                            INSERT INTO career_report_unit (report_id, unit_code, student_quote, reason)
                            VALUES (:id, :unit, :quote, :reason)""")
                    .param("id", saved.id())
                    .param("unit", e.getKey())
                    .param("quote", e.getValue()[0])
                    .param("reason", e.getValue()[1])
                    .update();
        }
        return saved;
    }

    void delete(long userId) {
        db.sql("DELETE FROM career_report WHERE user_id = :user").param("user", userId).update();
    }
}
