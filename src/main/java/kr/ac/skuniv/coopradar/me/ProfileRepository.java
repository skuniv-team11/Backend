package kr.ac.skuniv.coopradar.me;

import java.time.Instant;
import java.sql.Array;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import kr.ac.skuniv.coopradar.common.Times;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** student_profile 접근. 프로필 값은 로그에 남기지 않는다(ADR-0008). */
@Repository
public class ProfileRepository {

    private final JdbcClient db;

    public ProfileRepository(JdbcClient db) {
        this.db = db;
    }

    public Optional<Integer> findDepartmentId(String name) {
        return db.sql("SELECT id FROM department WHERE name = :name").param("name", name).query(Integer.class).optional();
    }

    public boolean areaExists(String code) {
        return db.sql("SELECT EXISTS (SELECT 1 FROM area WHERE code = :code)").param("code", code)
                .query(Boolean.class).single();
    }

    /**
     * 자격증 코드표(certificate)에 있는 코드만 코드표 순서로, 중복 없이(ADR-0021). 프로필 저장 검사와 예시 프로필에 쓴다.
     */
    public List<String> knownCertificates(List<String> codes) {
        if (codes.isEmpty()) {
            return List.of();
        }
        return db.sql("SELECT code FROM certificate WHERE code IN (:codes) ORDER BY sort_order, code")
                .param("codes", codes)
                .query(String.class)
                .list();
    }

    /** 체험 계정의 예시 프로필. 시연용 가상 학생이라 동의 시각은 만든 시각으로 둔다. */
    public void insertExample(long userId, int departmentId, ExampleProfileProperties p, String homeAreaCode,
                              List<String> certificates, Instant now) {
        db.sql("""
                        INSERT INTO student_profile (user_id, department_id, grade, completed_semesters, gpa,
                                                     graduation_expected, interest_text, home_area_code, certificates,
                                                     consented_at, updated_at)
                        VALUES (:userId, :departmentId, :grade, :semesters, :gpa, :graduationExpected, :interest, :area,
                                CAST(:certificates AS varchar(40)[]), :now, :now)""")
                .param("userId", userId)
                .param("departmentId", departmentId)
                .param("grade", p.grade())
                .param("semesters", p.completedSemesters())
                .param("gpa", p.gpa())
                .param("graduationExpected", p.graduationExpected())
                .param("interest", p.interestText())
                .param("area", homeAreaCode)
                .param("certificates", array(certificates))
                .param("now", Times.utc(now))
                .update();
    }

    public Optional<ProfileView> findView(long userId, boolean isExample) {
        return findSaved(userId, isExample).map(SavedProfile::toView);
    }

    /** 저장한 프로필(#8·#9 응답). 시각은 한국 시간으로 돌려준다. */
    public Optional<SavedProfile> findSaved(long userId, boolean isExample) {
        return db.sql("""
                        SELECT p.department_id, p.grade, p.completed_semesters, p.gpa, p.graduation_expected, p.interest_text,
                               p.home_area_code, p.certificates, p.consented_at, p.updated_at,
                               d.name AS department_name, a.sido AS area_sido, a.name AS area_name
                        FROM student_profile p
                        JOIN department d ON d.id = p.department_id
                        LEFT JOIN area a ON a.code = p.home_area_code
                        WHERE p.user_id = :userId""")
                .param("userId", userId)
                .query((rs, i) -> {
                    String areaCode = rs.getString("home_area_code");
                    return new SavedProfile(
                            rs.getInt("department_id"),
                            rs.getInt("grade"),
                            rs.getInt("completed_semesters"),
                            rs.getBigDecimal("gpa"),
                            rs.getBoolean("graduation_expected"),
                            rs.getString("interest_text"),
                            areaCode,
                            list(rs.getArray("certificates")),
                            new ProfileView.DepartmentRef(rs.getInt("department_id"), rs.getString("department_name")),
                            areaCode == null ? null
                                    : new ProfileView.AreaView(areaCode, rs.getString("area_sido"), rs.getString("area_name")),
                            isExample,
                            Times.kst(rs.getObject("consented_at", OffsetDateTime.class)),
                            Times.kst(rs.getObject("updated_at", OffsetDateTime.class)));
                })
                .optional();
    }

    public boolean departmentExists(int id) {
        return db.sql("SELECT EXISTS (SELECT 1 FROM department WHERE id = :id)").param("id", id)
                .query(Boolean.class).single();
    }

    /**
     * [저장]+동의(#9). 있으면 덮어쓰고 동의 시각도 이번 저장 시각으로 바꾼다.
     *
     * @param certificates 서비스가 코드표로 확인·정렬한 자격증 코드. null이면 답하지 않음
     */
    public void upsert(long userId, ProfileSaveRequest p, List<String> certificates, Instant now) {
        db.sql("""
                        INSERT INTO student_profile (user_id, department_id, grade, completed_semesters, gpa,
                                                     graduation_expected, interest_text, home_area_code, certificates,
                                                     consented_at, updated_at)
                        VALUES (:userId, :departmentId, :grade, :semesters, :gpa, :graduationExpected, :interest, :area,
                                CAST(:certificates AS varchar(40)[]), :now, :now)
                        ON CONFLICT (user_id) DO UPDATE SET
                            department_id       = EXCLUDED.department_id,
                            grade               = EXCLUDED.grade,
                            completed_semesters = EXCLUDED.completed_semesters,
                            gpa                 = EXCLUDED.gpa,
                            graduation_expected = EXCLUDED.graduation_expected,
                            interest_text       = EXCLUDED.interest_text,
                            home_area_code      = EXCLUDED.home_area_code,
                            certificates        = EXCLUDED.certificates,
                            consented_at        = EXCLUDED.consented_at,
                            updated_at          = EXCLUDED.updated_at""")
                .param("userId", userId)
                .param("departmentId", p.departmentId())
                .param("grade", p.grade())
                .param("semesters", p.completedSemesters())
                .param("gpa", p.gpa())
                .param("graduationExpected", p.graduationExpected())
                .param("interest", p.interestText())
                .param("area", p.homeAreaCode())
                .param("certificates", array(certificates))
                .param("now", Times.utc(now))
                .update();
    }

    /** 배열 칸 값. 목록(Iterable)으로 넘기면 IN 목록처럼 풀려 버려서 배열로 바꿔 넘긴다. */
    private static String[] array(List<String> codes) {
        return codes == null ? null : codes.toArray(String[]::new);
    }

    private static List<String> list(Array array) throws SQLException {
        return array == null ? null : List.of((String[]) array.getArray());
    }

    /** 프로필만 지운다(계정·담은 지망은 남는다, #10). 없어도 그대로 끝난다. */
    public void delete(long userId) {
        db.sql("DELETE FROM student_profile WHERE user_id = :userId").param("userId", userId).update();
    }
}
