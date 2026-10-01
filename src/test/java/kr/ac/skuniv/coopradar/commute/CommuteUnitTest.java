package kr.ac.skuniv.coopradar.commute;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import kr.ac.skuniv.coopradar.commute.CommuteProperties.Point;
import kr.ac.skuniv.coopradar.commute.KakaoTransitClient.Outcome;
import kr.ac.skuniv.coopradar.commute.KakaoTransitClient.Result;
import org.junit.jupiter.api.Test;

/** DB 없이 도는 단위 테스트: 카카오 응답 해석, 분 반올림, 호출 제한. */
class CommuteUnitTest {

    // ───────── 카카오 응답 해석 ─────────

    @Test
    void 상태_OK면_첫_경로의_시간_환승_요금() {
        Result r = parse(200, """
                {"status":"OK","routes":[
                  {"properties":{"totalTime":2580,"transfers":1,"fare":{"value":1550}}},
                  {"properties":{"totalTime":1200,"transfers":0,"fare":{"value":1400}}}]}""");
        assertThat(r).isEqualTo(new Result(Outcome.OK, 2580, 1, 1550));
    }

    @Test
    void 요금이나_환승이_없으면_null() {
        assertThat(parse(200, "{\"status\":\"OK\",\"routes\":[{\"properties\":{\"totalTime\":600}}]}"))
                .isEqualTo(new Result(Outcome.OK, 600, null, null));
    }

    @Test
    void 경로_없음_상태는_NO_ROUTE() {
        for (String s : new String[] {"NO_RESULTS", "EQUAL_POINTS", "STARTNODES_NULL", "ENDNODES_NULL"}) {
            assertThat(parse(200, "{\"status\":\"" + s + "\"}").outcome()).as(s).isEqualTo(Outcome.NO_ROUTE);
        }
    }

    @Test
    void 한도_초과는_LIMITED_나머지는_ERROR() {
        assertThat(parse(400, "{\"code\":-10,\"msg\":\"API limit has been exceeded.\"}").outcome()).isEqualTo(Outcome.LIMITED);
        assertThat(parse(429, "").outcome()).isEqualTo(Outcome.LIMITED);
        assertThat(parse(401, "{\"code\":-401,\"msg\":\"wrong appKey\"}").outcome()).isEqualTo(Outcome.ERROR);
        assertThat(parse(200, "{\"status\":\"INVALID_REQUEST\"}").outcome()).isEqualTo(Outcome.ERROR);
        assertThat(parse(200, "{\"status\":\"OK\",\"routes\":[]}").outcome()).isEqualTo(Outcome.ERROR);
        assertThat(parse(200, "<html>").outcome()).isEqualTo(Outcome.ERROR);
        assertThat(parse(502, "").outcome()).isEqualTo(Outcome.ERROR);
    }

    @Test
    void 분은_초를_60으로_나눠_반올림하고_최소_1분() {
        assertThat(CommuteService.minutes(2580)).isEqualTo(43);
        assertThat(CommuteService.minutes(2609)).isEqualTo(43);
        assertThat(CommuteService.minutes(2610)).isEqualTo(44);
        assertThat(CommuteService.minutes(10)).isEqualTo(1);
    }

    // ───────── 호출 제한 ─────────

    @Test
    void 계정당_1시간_한도는_한_시간이_지나면_풀린다() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-01T05:00:00Z"));
        CommuteLimiter limiter = new CommuteLimiter(props(2, 100), clock);
        assertThat(limiter.tryAcquire(1)).isTrue();
        assertThat(limiter.tryAcquire(1)).isTrue();
        assertThat(limiter.tryAcquire(1)).isFalse();
        assertThat(limiter.tryAcquire(2)).isTrue();
        clock.advance(Duration.ofMinutes(61));
        assertThat(limiter.tryAcquire(1)).isTrue();
    }

    @Test
    void 서버_하루_한도는_한국_시간_자정에_새로_센다() {
        // 2026-10-01 23:50 KST
        MutableClock clock = new MutableClock(Instant.parse("2026-10-01T14:50:00Z"));
        CommuteLimiter limiter = new CommuteLimiter(props(0, 2), clock);
        assertThat(limiter.tryAcquire(1)).isTrue();
        assertThat(limiter.tryAcquire(2)).isTrue();
        assertThat(limiter.tryAcquire(3)).isFalse();
        clock.advance(Duration.ofMinutes(9)); // 23:59 KST, 아직 같은 날
        assertThat(limiter.tryAcquire(3)).isFalse();
        clock.advance(Duration.ofMinutes(2)); // 10/2 00:01 KST
        assertThat(limiter.tryAcquire(3)).isTrue();
    }

    @Test
    void 막힌_호출은_세지_않는다() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-01T05:00:00Z"));
        CommuteLimiter limiter = new CommuteLimiter(props(1, 2), clock);
        assertThat(limiter.tryAcquire(1)).isTrue();
        assertThat(limiter.tryAcquire(1)).isFalse(); // 계정 한도 — 하루 한도는 그대로
        assertThat(limiter.tryAcquire(2)).isTrue();
        assertThat(limiter.tryAcquire(3)).isFalse(); // 하루 한도
    }

    private static Result parse(int status, String body) {
        return KakaoTransitClient.parse(status, body.getBytes(StandardCharsets.UTF_8));
    }

    private static CommuteProperties props(int perUserPerHour, int perDay) {
        return new CommuteProperties("http://127.0.0.1:1", "k", Duration.ofSeconds(3), perUserPerHour, perDay,
                new Point(new BigDecimal("37.615"), new BigDecimal("127.0131")));
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
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
