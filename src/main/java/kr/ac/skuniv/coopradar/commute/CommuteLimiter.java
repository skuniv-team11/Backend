package kr.ac.skuniv.coopradar.commute;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import kr.ac.skuniv.coopradar.common.Times;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 통근 조회 호출 제한(docs/api '호출 제한', ADR-0007): 계정당 1시간 30회, 서버 전체 하루 900회.
 * 카카오 무료 하루 1,000건 안에서 서버가 먼저 멈추게 한다. 서버 메모리에만 두므로 서버가 다시 뜨면 0부터 센다.
 * 카카오를 실제로 부를 때만 센다(근무지 좌표가 없어 부르지 않는 경우는 세지 않는다).
 */
@Component
public class CommuteLimiter {

    private static final Duration WINDOW = Duration.ofHours(1);

    private final Map<Long, Deque<Instant>> perUser = new HashMap<>();
    private final int perUserPerHour;
    private final int perDay;
    private final Clock clock;
    private LocalDate day;
    private int usedToday;

    public CommuteLimiter(CommuteProperties props, Clock clock) {
        this.perUserPerHour = props.perUserPerHour();
        this.perDay = props.perDay();
        this.clock = clock;
    }

    /** 두 한도 안이면 한 번 세고 true. 하나라도 넘었으면 세지 않고 false. */
    public synchronized boolean tryAcquire(long userId) {
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, Times.KST);
        if (!today.equals(day)) {
            day = today;
            usedToday = 0;
        }
        if (perDay > 0 && usedToday >= perDay) {
            return false;
        }
        Deque<Instant> q = perUser.computeIfAbsent(userId, k -> new ArrayDeque<>());
        evict(q, now);
        if (perUserPerHour > 0 && q.size() >= perUserPerHour) {
            return false;
        }
        q.addLast(now);
        usedToday++;
        return true;
    }

    /** 1시간 넘게 부르지 않은 계정을 지운다. */
    @Scheduled(initialDelayString = "PT10M", fixedDelayString = "PT10M")
    public synchronized void purge() {
        Instant now = clock.instant();
        perUser.values().removeIf(q -> {
            evict(q, now);
            return q.isEmpty();
        });
    }

    private static void evict(Deque<Instant> q, Instant now) {
        Instant from = now.minus(WINDOW);
        while (!q.isEmpty() && !q.peekFirst().isAfter(from)) {
            q.pollFirst();
        }
    }
}
