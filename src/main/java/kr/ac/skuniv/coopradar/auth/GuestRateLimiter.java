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
 * 체험 계정 만들기 IP당 1시간 한도(docs/api '호출 제한', ADR-0013). 서버 메모리에만 두고 1시간이 지나면 버린다.
 * 시연장·학교 와이파이처럼 여러 사람이 한 공인 IP를 쓰는 곳을 막지 않도록 넉넉하게 두고(기본 300),
 * 비용이 드는 호출(이유 문장·통근)은 각자의 한도가 지킨다. 0이면 제한하지 않는다.
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

    /** 한도 안이면 한 번 세고 {@link Duration#ZERO}, 넘었으면 다시 될 때까지 남은 시간(초 단위 올림). */
    public Duration acquire(String ip) {
        if (limit <= 0) {
            return Duration.ZERO;
        }
        Instant now = clock.instant();
        Deque<Instant> q = hits.computeIfAbsent(ip, k -> new ArrayDeque<>());
        synchronized (q) {
            evict(q, now);
            if (q.size() >= limit) {
                Duration wait = Duration.between(now, q.peekFirst().plus(WINDOW));
                return Duration.ofSeconds(Math.max(1, (wait.toMillis() + 999) / 1000));
            }
            q.addLast(now);
            return Duration.ZERO;
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
