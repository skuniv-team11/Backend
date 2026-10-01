package kr.ac.skuniv.coopradar.me;

import java.time.Instant;
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

    /** 체험 계정의 예시 프로필. 시연용 가상 학생이라 동의 시각은 만든 시각으로 둔다. */
    public void insertExample(long userId, int departmentId, ExampleProfileProperties p, String homeAreaCode, Instant now) {
        db.sql("""
                        INSERT INTO student_profile (user_id, department_id, grade, completed_semesters, gpa,
                                                     graduation_expected, interest_text, home_area_code, consented_at, updated_at)
                        VALUES (:userId, :departmentId, :grade, :semesters, :gpa, :graduationExpected, :interest, :area, :now, :now)""")
                .param("userId", userId)
                .param("departmentId", departmentId)
                .param("grade", p.grade())
                .param("semesters", p.completedSemesters())
                .param("gpa", p.gpa())
                .param("graduationExpected", p.graduationExpected())
                .param("interest", p.interestText())
                .param("area", homeAreaCode)
                .param("now", Times.utc(now))
                .update();
    }

    public Optional<ProfileView> findView(long userId, boolean isExample) {
        return db.sql("""
                        SELECT p.department_id, p.grade, p.completed_semesters, p.gpa, p.graduation_expected, p.interest_text,
                               p.home_area_code, d.name AS department_name, a.sido AS area_sido, a.name AS area_name
                        FROM student_profile p
                        JOIN department d ON d.id = p.department_id
                        LEFT JOIN area a ON a.code = p.home_area_code
                        WHERE p.user_id = :userId""")
                .param("userId", userId)
                .query((rs, i) -> {
                    String areaCode = rs.getString("home_area_code");
                    return new ProfileView(
                            rs.getInt("department_id"),
                            rs.getInt("grade"),
                            rs.getInt("completed_semesters"),
                            rs.getBigDecimal("gpa"),
                            rs.getBoolean("graduation_expected"),
                            rs.getString("interest_text"),
                            areaCode,
                            new ProfileView.DepartmentRef(rs.getInt("department_id"), rs.getString("department_name")),
                            areaCode == null ? null
                                    : new ProfileView.AreaView(areaCode, rs.getString("area_sido"), rs.getString("area_name")),
                            isExample);
                })
                .optional();
    }
}
