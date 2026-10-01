package kr.ac.skuniv.coopradar.signal;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import kr.ac.skuniv.coopradar.common.ApiException;
import kr.ac.skuniv.coopradar.common.ErrorCode;
import kr.ac.skuniv.coopradar.reference.ReferenceDtos.CurrentRound;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 회차 직무 전부의 asOf 기준 모집 신호. 지망 점검(#23)과 센터 현황판(#24)이 같이 쓴다. */
@Service
public class SignalService {

    private final SignalRepository signals;
    private final JdbcClient db;

    public SignalService(SignalRepository signals, JdbcClient db) {
        this.signals = signals;
        this.db = db;
    }

    /**
     * asOf를 정한다. 생략하면 rounds/current의 replay.defaultAsOf, 주면 회차 모집기간 안이어야 한다
     * (아니면 400 AS_OF_OUT_OF_RANGE).
     */
    public static LocalDate resolveAsOf(CurrentRound round, LocalDate requested) {
        if (requested == null) {
            return round.replay().defaultAsOf();
        }
        if (requested.isBefore(round.recruitStart()) || requested.isAfter(round.recruitEnd())) {
            throw new ApiException(ErrorCode.AS_OF_OUT_OF_RANGE,
                    "기준일은 모집기간(" + round.recruitStart() + "~" + round.recruitEnd() + ") 안이어야 해요");
        }
        return requested;
    }

    /** 직무 id → 신호. 회차의 직무가 모두 들어 있다(신호가 없는 직무는 0). */
    @Transactional(readOnly = true)
    public Map<Integer, Signal> byJob(CurrentRound round, LocalDate asOf) {
        var daily = signals.dailyByJob(round.id());
        Map<Integer, Signal> out = new HashMap<>();
        db.sql("""
                        SELECT id, headcount, closes_on, close_reason, closes_on_is_virtual
                        FROM job WHERE round_id = :round""")
                .param("round", round.id())
                .query(rs -> {
                    int jobId = rs.getInt("id");
                    out.put(jobId, SignalCalculator.compute(daily.getOrDefault(jobId, Map.of()), rs.getInt("headcount"),
                            rs.getObject("closes_on", LocalDate.class), rs.getString("close_reason"),
                            rs.getBoolean("closes_on_is_virtual"), round.recruitStart(), round.recruitEnd(), asOf));
                });
        return out;
    }
}
