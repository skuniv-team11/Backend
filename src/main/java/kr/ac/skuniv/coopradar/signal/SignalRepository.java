package kr.ac.skuniv.coopradar.signal;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import kr.ac.skuniv.coopradar.signal.SignalCalculator.Daily;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 리플레이 일별 신호(replay_signal, 가상 시드) 읽기. 누적이 아니라 그날 새로 생긴 수다. */
@Repository
public class SignalRepository {

    private final JdbcClient db;

    public SignalRepository(JdbcClient db) {
        this.db = db;
    }

    /** 회차 직무 전부의 일별 신호. 직무 id → 날짜 → 수. 신호가 하나도 없는 직무는 빠진다. */
    public Map<Integer, Map<LocalDate, Daily>> dailyByJob(int roundId) {
        Map<Integer, Map<LocalDate, Daily>> out = new HashMap<>();
        db.sql("""
                        SELECT s.job_id, s.signal_date, s.interest_count, s.intent_count
                        FROM replay_signal s
                        JOIN job j ON j.id = s.job_id
                        WHERE j.round_id = :round""")
                .param("round", roundId)
                .query(rs -> {
                    out.computeIfAbsent(rs.getInt("job_id"), k -> new HashMap<>())
                            .put(rs.getObject("signal_date", LocalDate.class),
                                    new Daily(rs.getInt("interest_count"), rs.getInt("intent_count")));
                });
        return out;
    }
}
