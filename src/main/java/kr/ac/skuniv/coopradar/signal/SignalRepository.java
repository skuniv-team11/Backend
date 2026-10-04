package kr.ac.skuniv.coopradar.signal;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 관심 신호 읽기: 리플레이 일별 가상 값(replay_signal)과 실제 담은 수(plan_item). */
@Repository
public class SignalRepository {

    private final JdbcClient db;

    public SignalRepository(JdbcClient db) {
        this.db = db;
    }

    /** 회차 직무 전부의 일별 가상 관심. 직무 id → 날짜 → 그날 새로 생긴 수. 신호가 하나도 없는 직무는 빠진다. */
    public Map<Integer, Map<LocalDate, Integer>> dailyByJob(int roundId) {
        Map<Integer, Map<LocalDate, Integer>> out = new HashMap<>();
        db.sql("""
                        SELECT s.job_id, s.signal_date, s.interest_count
                        FROM replay_signal s
                        JOIN job j ON j.id = s.job_id
                        WHERE j.round_id = :round""")
                .param("round", roundId)
                .query(rs -> {
                    out.computeIfAbsent(rs.getInt("job_id"), k -> new HashMap<>())
                            .put(rs.getObject("signal_date", LocalDate.class), rs.getInt("interest_count"));
                });
        return out;
    }

    /**
     * 회차 직무별 실제로 담은 사람 수(plan_item, 순위 무관, 1인 1표, 체험 계정 포함). 담은 사람이 없는 직무는 빠진다.
     *
     * @param excludeUserId 빼고 셀 계정(지망 점검은 본인을 빼고 다른 사람 수만 보여 준다). null이면 모두 센다
     */
    public Map<Integer, Integer> liveByJob(int roundId, Long excludeUserId) {
        Map<Integer, Integer> out = new HashMap<>();
        db.sql("""
                        SELECT p.job_id, count(*) AS n
                        FROM plan_item p
                        JOIN job j ON j.id = p.job_id
                        WHERE j.round_id = :round AND p.user_id <> :exclude
                        GROUP BY p.job_id""")
                .param("round", roundId)
                .param("exclude", excludeUserId == null ? -1L : excludeUserId)
                .query(rs -> {
                    out.put(rs.getInt("job_id"), rs.getInt("n"));
                });
        return out;
    }
}
