package kr.ac.skuniv.coopradar.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import kr.ac.skuniv.coopradar.common.ApiException;
import kr.ac.skuniv.coopradar.common.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

/** DB 없이 도는 단위 테스트: 역할 확인, 호출 제한, 서명 키 규칙. */
class AuthUnitTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-01T05:00:00Z"));
    private final AuthProperties props =
            new AuthProperties(SECRET, Duration.ofDays(7), Duration.ofHours(24), 3, "CF-Connecting-IP");

    // ───────── 역할 ─────────

    @RequireRole(Role.CENTER)
    static class CenterOnly {
        public void board() {
        }

        @RequireRole(Role.STUDENT)
        public void studentOverride() {
        }

        @PublicApi
        public void open() {
        }
    }

    @Test
    void 역할이_다르면_403_FORBIDDEN_ROLE_메서드_표시가_클래스보다_먼저() throws Exception {
        JwtService jwt = new JwtService(props, clock);
        AuthInterceptor interceptor = new AuthInterceptor(jwt, usersWith(new AuthUser(7, Role.STUDENT, false, null)), clock);
        String token = jwt.issue(7, Role.STUDENT, false, clock.instant().plusSeconds(60));

        assertThatThrownBy(() -> interceptor.preHandle(withToken(token), new MockHttpServletResponse(), handler("board")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.FORBIDDEN_ROLE));
        assertThat(interceptor.preHandle(withToken(token), new MockHttpServletResponse(), handler("studentOverride"))).isTrue();
        assertThat(interceptor.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), handler("open"))).isTrue();
    }

    @Test
    void 토큰의_역할이_아니라_DB의_역할을_본다() throws Exception {
        JwtService jwt = new JwtService(props, clock);
        // 토큰에는 CENTER라고 적혀 있어도 DB가 STUDENT면 막는다
        AuthInterceptor interceptor = new AuthInterceptor(jwt, usersWith(new AuthUser(8, Role.STUDENT, false, null)), clock);
        String token = jwt.issue(8, Role.CENTER, false, clock.instant().plusSeconds(60));
        assertThatThrownBy(() -> interceptor.preHandle(withToken(token), new MockHttpServletResponse(), handler("board")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.FORBIDDEN_ROLE));
    }

    // ───────── 호출 제한 ─────────

    @Test
    void 한도는_IP별로_1시간_창을_밀고_남은_시간을_알려준다() {
        GuestRateLimiter limiter = new GuestRateLimiter(props, clock); // 테스트 한도 3
        assertThat(limiter.acquire("a")).isZero();
        clock.advance(Duration.ofMinutes(10));
        assertThat(limiter.acquire("a")).isZero();
        assertThat(limiter.acquire("a")).isZero();
        assertThat(limiter.acquire("a")).isEqualTo(Duration.ofMinutes(50)); // 첫 요청이 빠질 때까지
        assertThat(limiter.acquire("b")).isZero();

        clock.advance(Duration.ofMinutes(49));
        assertThat(limiter.acquire("a")).isEqualTo(Duration.ofMinutes(1));
        clock.advance(Duration.ofMinutes(2));
        assertThat(limiter.acquire("a")).isZero();

        clock.advance(Duration.ofHours(2));
        limiter.purge();
        assertThat(limiter.acquire("a")).isZero();
    }

    @Test
    void 한도_0이면_제한하지_않는다() {
        GuestRateLimiter off = new GuestRateLimiter(
                new AuthProperties(SECRET, Duration.ofDays(7), Duration.ofHours(24), 0, null), clock);
        for (int i = 0; i < 1000; i++) {
            assertThat(off.acquire("a")).isZero();
        }
    }

    // ───────── 서명 키 ─────────

    @Test
    void 서명_키는_32바이트_이상이고_비우면_임시_키() throws Exception {
        assertThatThrownBy(() -> new JwtService(new AuthProperties("short", null, null, 1, null), clock))
                .isInstanceOf(IllegalStateException.class);
        JwtService a = new JwtService(new AuthProperties("", null, null, 1, null), clock);
        JwtService b = new JwtService(new AuthProperties(" ", null, null, 1, null), clock);
        String token = a.issue(1, Role.STUDENT, false, clock.instant().plusSeconds(60));
        assertThat(a.verify(token)).isEqualTo(1);
        assertThatThrownBy(() -> b.verify(token)) // 다른 임시 키로는 검증 실패
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.AUTH_REQUIRED));
    }

    // ───────── 도우미 ─────────

    private static HandlerMethod handler(String name) throws NoSuchMethodException {
        return new HandlerMethod(new CenterOnly(), CenterOnly.class.getMethod(name));
    }

    private static MockHttpServletRequest withToken(String token) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.addHeader("Authorization", "Bearer " + token);
        return r;
    }

    private static UserRepository usersWith(AuthUser user) {
        return new UserRepository(null) {
            @Override
            public Optional<AuthUser> findAuthUser(long id) {
                return id == user.id() ? Optional.of(user) : Optional.empty();
            }
        };
    }

    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
