package kr.ac.skuniv.coopradar.auth;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import kr.ac.skuniv.coopradar.common.Times;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** app_user 접근. 이메일은 소문자로 맞춰 넣고 찾는다(유니크 인덱스도 lower(email)). */
@Repository
public class UserRepository {

    private final JdbcClient db;

    public UserRepository(JdbcClient db) {
        this.db = db;
    }

    public record Credentials(long id, String passwordHash) {
    }

    /** 계정 칸(GET /api/me) */
    public record Account(long id, String email, Role role, boolean guest, OffsetDateTime expiresAt, boolean hasProfile) {
    }

    public long insertMember(String email, String passwordHash) {
        return db.sql("INSERT INTO app_user (email, password_hash, role) VALUES (:email, :hash, 'STUDENT') RETURNING id")
                .param("email", email)
                .param("hash", passwordHash)
                .query(Long.class)
                .single();
    }

    public long insertGuest(Role role, Instant expiresAt) {
        return db.sql("INSERT INTO app_user (role, is_guest, expires_at) VALUES (:role, true, :expiresAt) RETURNING id")
                .param("role", role.name())
                .param("expiresAt", Times.utc(expiresAt))
                .query(Long.class)
                .single();
    }

    /** 가입 계정만. 체험 계정은 이메일이 없다. */
    public Optional<Credentials> findCredentials(String email) {
        return db.sql("SELECT id, password_hash FROM app_user WHERE lower(email) = :email AND NOT is_guest")
                .param("email", email)
                .query((rs, i) -> new Credentials(rs.getLong("id"), rs.getString("password_hash")))
                .optional();
    }

    public Optional<AuthUser> findAuthUser(long id) {
        return db.sql("SELECT id, role, is_guest, expires_at FROM app_user WHERE id = :id")
                .param("id", id)
                .query((rs, i) -> {
                    OffsetDateTime exp = rs.getObject("expires_at", OffsetDateTime.class);
                    return new AuthUser(rs.getLong("id"), Role.valueOf(rs.getString("role")), rs.getBoolean("is_guest"),
                            exp == null ? null : exp.toInstant());
                })
                .optional();
    }

    public Optional<Account> findAccount(long id) {
        return db.sql("""
                        SELECT u.id, u.email, u.role, u.is_guest, u.expires_at,
                               EXISTS (SELECT 1 FROM student_profile p WHERE p.user_id = u.id) AS has_profile
                        FROM app_user u WHERE u.id = :id""")
                .param("id", id)
                .query((rs, i) -> new Account(rs.getLong("id"), rs.getString("email"), Role.valueOf(rs.getString("role")),
                        rs.getBoolean("is_guest"), rs.getObject("expires_at", OffsetDateTime.class),
                        rs.getBoolean("has_profile")))
                .optional();
    }

    /** 계정을 지우면 프로필·담은 지망도 DB cascade로 같이 지워진다(ADR-0008). */
    public boolean delete(long id) {
        return db.sql("DELETE FROM app_user WHERE id = :id").param("id", id).update() == 1;
    }

    public int deleteExpiredGuests(Instant now) {
        int deleted = db.sql("DELETE FROM app_user WHERE is_guest AND expires_at <= :now")
                .param("now", Times.utc(now))
                .update();
        // 체험 묶음(ADR-0033): 마지막 체험 계정과 같이 만료된다. 지우면 가상 지원자도 함께 지워진다(cascade)
        db.sql("DELETE FROM demo_group WHERE expires_at <= :now").param("now", Times.utc(now)).update();
        return deleted;
    }
}
