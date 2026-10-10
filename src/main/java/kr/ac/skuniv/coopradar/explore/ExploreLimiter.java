package kr.ac.skuniv.coopradar.explore;

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
 * 직무 탐색 AI 사용 제한(docs/api '호출 제한', ADR-0031): 계정당 1시간 10회, IP당 1시간 60회, 서버 전체 하루 300회(초기값).
 * 탐색 1번(순서 + 상위 3곳 설명)과 '왜 맞나요' 1곳이 각각 1회다. AI를 실제로 부를 때만 센다(저장본을 줄 때는 세지 않음).
 * 서버 메모리에만 두므로 서버가 다시 뜨면 0부터 센다. IP는 저장·로그하지 않는다.
 */
@Component
public class ExploreLimiter {

    private static final Duration WINDOW = Duration.ofHours(1);

    private final Map<Long, Deque<Instant>> perUser = new HashMap<>();
    private final Map<String, Deque<Instant>> perIp = new HashMap<>();
    private final ExploreProperties props;
    private final Clock clock;
    private LocalDate day;
    private int usedToday;

    public ExploreLimiter(ExploreProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
    }

    /** 세 한도 안이면 한 번 세고 true. 하나라도 넘었으면 세지 않고 false. */
    public synchronized boolean tryAcquire(long userId, String ip) {
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, Times.KST);
        if (!today.equals(day)) {
            day = today;
            usedToday = 0;
        }
        Deque<Instant> u = perUser.computeIfAbsent(userId, k -> new ArrayDeque<>());
        Deque<Instant> i = perIp.computeIfAbsent(ip, k -> new ArrayDeque<>());
        evict(u, now);
        evict(i, now);
        if ((props.perDay() > 0 && usedToday >= props.perDay())
                || (props.perUserPerHour() > 0 && u.size() >= props.perUserPerHour())
                || (props.perIpPerHour() > 0 && i.size() >= props.perIpPerHour())) {
            return false;
        }
        u.addLast(now);
        i.addLast(now);
        usedToday++;
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
