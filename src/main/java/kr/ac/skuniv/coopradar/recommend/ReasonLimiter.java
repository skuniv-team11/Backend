package kr.ac.skuniv.coopradar.recommend;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 이유 문장 LLM 호출 제한(docs/api '호출 제한'): 계정당 1시간 30회, IP당 1시간 60회. 넘으면 기본 문장으로 답한다.
 * LLM을 실제로 부를 때만 센다(캐시에서 준 문장은 세지 않음). 서버 메모리에만 둔다. IP는 저장·로그하지 않는다.
 */
@Component
public class ReasonLimiter {

    private static final Duration WINDOW = Duration.ofHours(1);

    private final Map<Long, Deque<Instant>> perUser = new HashMap<>();
    private final Map<String, Deque<Instant>> perIp = new HashMap<>();
    private final int perUserPerHour;
    private final int perIpPerHour;
    private final Clock clock;

    public ReasonLimiter(ReasonProperties props, Clock clock) {
        this.perUserPerHour = props.perUserPerHour();
        this.perIpPerHour = props.perIpPerHour();
        this.clock = clock;
    }

    /** 두 한도 안이면 한 번 세고 true. 하나라도 넘었으면 세지 않고 false. */
    public synchronized boolean tryAcquire(long userId, String ip) {
        Instant now = clock.instant();
        Deque<Instant> u = perUser.computeIfAbsent(userId, k -> new ArrayDeque<>());
        Deque<Instant> i = perIp.computeIfAbsent(ip, k -> new ArrayDeque<>());
        evict(u, now);
        evict(i, now);
        if ((perUserPerHour > 0 && u.size() >= perUserPerHour) || (perIpPerHour > 0 && i.size() >= perIpPerHour)) {
            return false;
        }
        u.addLast(now);
        i.addLast(now);
        return true;
    }

    @Scheduled(initialDelayString = "PT10M", fixedDelayString = "PT10M")
    public synchronized void purge() {
        Instant now = clock.instant();
        perUser.values().removeIf(q -> {
            evict(q, now);
            return q.isEmpty();
        });
        perIp.values().removeIf(q -> {
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
