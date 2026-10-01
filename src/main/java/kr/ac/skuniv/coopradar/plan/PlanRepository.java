package kr.ac.skuniv.coopradar.plan;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import kr.ac.skuniv.coopradar.common.Times;
import kr.ac.skuniv.coopradar.job.InstitutionRef;
import kr.ac.skuniv.coopradar.plan.PlanDtos.PlanItem;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 담은 직무(plan_item). 실행 중에 쓰는 계정 테이블 3개 중 하나다(V1). 계정이 지워지면 cascade로 같이 지워진다. */
@Repository
public class PlanRepository {

    private final JdbcClient db;

    public PlanRepository(JdbcClient db) {
        this.db = db;
    }

    public List<PlanItem> items(long userId) {
        return db.sql("""
                        SELECT p.job_id, j.title, j.institution_id, i.name AS institution_name, p.rank, p.added_at
                        FROM plan_item p
                        JOIN job j ON j.id = p.job_id
                        JOIN institution i ON i.id = j.institution_id
                        WHERE p.user_id = :user
                        ORDER BY p.rank NULLS LAST, p.added_at, p.job_id""")
                .param("user", userId)
                .query((rs, n) -> new PlanItem(
                        rs.getInt("job_id"),
                        rs.getString("title"),
                        new InstitutionRef(rs.getInt("institution_id"), rs.getString("institution_name")),
                        (Integer) rs.getObject("rank"),
                        Times.kst(rs.getObject("added_at", OffsetDateTime.class))))
                .list();
    }

    /** 현재 회차의 직무인지. */
    public boolean jobInRound(long jobId, int roundId) {
        return db.sql("SELECT EXISTS (SELECT 1 FROM job WHERE id = :id AND round_id = :round)")
                .param("id", jobId).param("round", roundId)
                .query(Boolean.class).single();
    }

    /** 새로 담았으면 true, 이미 담겨 있었으면 false(그대로 둔다). */
    public boolean add(long userId, long jobId, Instant now) {
        return db.sql("""
                        INSERT INTO plan_item (user_id, job_id, added_at) VALUES (:user, :job, :now)
                        ON CONFLICT (user_id, job_id) DO NOTHING""")
                .param("user", userId).param("job", jobId).param("now", Times.utc(now))
                .update() == 1;
    }

    /** 지웠으면 true. */
    public boolean remove(long userId, long jobId) {
        return db.sql("DELETE FROM plan_item WHERE user_id = :user AND job_id = :job")
                .param("user", userId).param("job", jobId)
                .update() == 1;
    }

    public List<Integer> jobIds(long userId) {
        return db.sql("SELECT job_id FROM plan_item WHERE user_id = :user")
                .param("user", userId).query(Integer.class).list();
    }

    /** 순위를 모두 지운 뒤 다시 매긴다. 부분 유니크 인덱스(user_id, rank)와 부딪히지 않게 두 단계로 한다. */
    public void replaceRanks(long userId, List<PlanDtos.RankEntry> ranks) {
        db.sql("UPDATE plan_item SET rank = NULL WHERE user_id = :user AND rank IS NOT NULL")
                .param("user", userId).update();
        for (PlanDtos.RankEntry r : ranks) {
            db.sql("UPDATE plan_item SET rank = :rank WHERE user_id = :user AND job_id = :job")
                    .param("rank", r.rank()).param("user", userId).param("job", r.jobId())
                    .update();
        }
    }
}
