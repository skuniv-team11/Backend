package kr.ac.skuniv.coopradar.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 체험 계정 만들기 IP당 1시간 한도(docs/api '호출 제한'). 서버 메모리에만 두고 1시간이 지나면 버린다.
 * 서버가 다시 뜨면 비워진다 — 심사 기간 남용을 늦추는 용도이고, 비용 상한은 Console 사용 한도가 맡는다.
 */
@Component
public class GuestRateLimiter {

    private static final Duration WINDOW = Duration.ofHours(1);

    private final Map<String, Deque<Instant>> hits = new ConcurrentHashMap<>();
    private final int limit;
    private final Clock clock;

    public GuestRateLimiter(AuthProperties props, Clock clock) {
        this.limit = props.guestPerIpPerHour();
        this.clock = clock;
    }

    /** 한도 안이면 한 번 세고 true. */
    public boolean tryAcquire(String ip) {
        Instant now = clock.instant();
        Deque<Instant> q = hits.computeIfAbsent(ip, k -> new ArrayDeque<>());
        synchronized (q) {
            evict(q, now);
            if (q.size() >= limit) {
                return false;
            }
            q.addLast(now);
            return true;
        }
    }

    /** 1시간 넘게 조용한 IP를 지운다(정리 작업이 부른다). */
    public void purge() {
        Instant now = clock.instant();
        hits.entrySet().removeIf(e -> {
            synchronized (e.getValue()) {
                evict(e.getValue(), now);
                return e.getValue().isEmpty();
            }
        });
    }

    private static void evict(Deque<Instant> q, Instant now) {
        Instant from = now.minus(WINDOW);
        while (!q.isEmpty() && !q.peekFirst().isAfter(from)) {
            q.pollFirst();
        }
    }
}
