package kr.ac.skuniv.coopradar.auth;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 24시간이 지난 체험 계정을 계정째 지운다(프로필·담은 지망은 cascade, ADR-0008).
 * 지우기 전이라도 만료된 체험 토큰은 인터셉터가 TOKEN_EXPIRED로 막는다.
 */
@Component
public class GuestCleanup {

    private static final Logger log = LoggerFactory.getLogger(GuestCleanup.class);

    private final UserRepository users;
    private final GuestRateLimiter limiter;
    private final Clock clock;

    public GuestCleanup(UserRepository users, GuestRateLimiter limiter, Clock clock) {
        this.users = users;
        this.limiter = limiter;
        this.clock = clock;
    }

    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "${app.auth.guest-cleanup-delay:PT10M}")
    public int run() {
        int deleted = users.deleteExpiredGuests(clock.instant());
        limiter.purge();
        if (deleted > 0) {
            log.info("만료된 체험 계정 {}개를 지웠습니다", deleted);
        }
        return deleted;
    }
}
