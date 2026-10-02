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

/** DB 없이 도는 단위 테스트: 카카오 응답 해석(대중교통·주소 검색), 주소 검색어, 분 반올림, 호출 제한. */
class CommuteUnitTest {

    // ───────── 카카오 응답 해석 ─────────

    @Test
    void 상태_OK면_가장_빠른_경로의_시간_환승_요금() {
        // 카카오는 routes 정렬 기준을 정하지 않는다 — 첫 경로가 아니라 totalTime이 가장 짧은 경로
        Result r = parse(200, """
                {"status":"OK","routes":[
                  {"properties":{"totalTime":2580,"transfers":1,"fare":{"value":1550}}},
                  {"properties":{"totalTime":1200,"transfers":0,"fare":{"value":1400}}},
                  {"properties":{"totalTime":1800,"transfers":2,"fare":{"value":1600}}}]}""");
        assertThat(r).isEqualTo(new Result(Outcome.OK, 1200, 0, 1400));
    }

    @Test
    void 시간이_같으면_환승이_적은_경로_그것도_같으면_먼저_온_경로() {
        assertThat(parse(200, """
                {"status":"OK","routes":[
                  {"properties":{"totalTime":1200,"transfers":2,"fare":{"value":1500}}},
                  {"properties":{"totalTime":1200,"transfers":1,"fare":{"value":1600}}},
                  {"properties":{"totalTime":1200,"transfers":1,"fare":{"value":1700}}}]}"""))
                .isEqualTo(new Result(Outcome.OK, 1200, 1, 1600));
        // 시간 값이 없는 경로는 건너뛴다
        assertThat(parse(200, """
                {"status":"OK","routes":[{"properties":{}},{"properties":{"totalTime":900,"transfers":0}}]}"""))
                .isEqualTo(new Result(Outcome.OK, 900, 0, null));
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

    // ───────── 카카오 주소 검색 해석 ─────────

    @Test
    void 주소_검색은_첫_결과의_x를_경도_y를_위도로() {
        KakaoLocalClient.Result r = geo(200, """
                {"meta":{"total_count":2},"documents":[
                  {"address_name":"서울 노원구","address_type":"REGION","x":"127.056232","y":"37.654358"},
                  {"address_name":"다른 곳","x":"126.9","y":"37.4"}]}""");
        assertThat(r.outcome()).isEqualTo(KakaoLocalClient.Outcome.OK);
        assertThat(r.point()).isEqualTo(new Point(new BigDecimal("37.654358"), new BigDecimal("127.056232")));
    }

    @Test
    void 결과가_없거나_국내_범위_밖이면_NOT_FOUND() {
        assertThat(geo(200, "{\"meta\":{\"total_count\":0},\"documents\":[]}").outcome())
                .isEqualTo(KakaoLocalClient.Outcome.NOT_FOUND);
        assertThat(geo(200, "{\"documents\":[{\"x\":\"0\",\"y\":\"0\"}]}").outcome())
                .isEqualTo(KakaoLocalClient.Outcome.NOT_FOUND);
    }

    @Test
    void 주소_검색_한도_초과는_LIMITED_나머지는_ERROR() {
        assertThat(geo(400, "{\"code\":-10,\"msg\":\"API limit has been exceeded.\"}").outcome())
                .isEqualTo(KakaoLocalClient.Outcome.LIMITED);
        assertThat(geo(429, "").outcome()).isEqualTo(KakaoLocalClient.Outcome.LIMITED);
        assertThat(geo(401, "{\"code\":-401,\"msg\":\"wrong appKey\"}").outcome()).isEqualTo(KakaoLocalClient.Outcome.ERROR);
        assertThat(geo(200, "{\"meta\":{}}").outcome()).isEqualTo(KakaoLocalClient.Outcome.ERROR);
        assertThat(geo(200, "{\"documents\":[{\"x\":\"\",\"y\":\"37.5\"}]}").outcome()).isEqualTo(KakaoLocalClient.Outcome.ERROR);
        assertThat(geo(200, "<html>").outcome()).isEqualTo(KakaoLocalClient.Outcome.ERROR);
    }

    @Test
    void 주소_검색_실패는_한도_초과를_먼저_보고_나머지는_PROVIDER_ERROR() {
        var ok = KakaoLocalClient.Outcome.OK;
        assertThat(CommuteService.failure(ok, ok)).isNull();
        assertThat(CommuteService.failure(KakaoLocalClient.Outcome.LIMITED, KakaoLocalClient.Outcome.ERROR))
                .isEqualTo(CommuteDtos.UnavailableReason.LIMITED);
        assertThat(CommuteService.failure(KakaoLocalClient.Outcome.NOT_FOUND, ok))
                .isEqualTo(CommuteDtos.UnavailableReason.PROVIDER_ERROR); // 사는 곳을 못 찾는 건 카카오 쪽 문제로 본다
        assertThat(CommuteService.failure(ok, KakaoLocalClient.Outcome.ERROR))
                .isEqualTo(CommuteDtos.UnavailableReason.PROVIDER_ERROR);
    }

    // ───────── 근로지 주소 → 검색어 ─────────

    @Test
    void 근로지_주소는_도로명과_건물번호까지만_남긴다() {
        // 2026-2 시드의 실제 근로지 주소 형태(건물명·층·호수가 섞여 있음)
        assertThat(AddressQuery.of("서울시 성동구 뚝섬로1길 25, 706-707호")).isEqualTo("서울 성동구 뚝섬로1길 25");
        assertThat(AddressQuery.of("서울시 서초구 강남대로61길 23, 203호 (서초동, 현대성우빌딩)")).isEqualTo("서울 서초구 강남대로61길 23");
        assertThat(AddressQuery.of("경기도 성남시 수정구 창업로 57번길 7, 5층")).isEqualTo("경기도 성남시 수정구 창업로57번길 7");
        assertThat(AddressQuery.of("서울시 영등포구 당산로 41길 11 당산 SK V1 Center W동 1008호")).isEqualTo("서울 영등포구 당산로41길 11");
        assertThat(AddressQuery.of("서울시 성동구 아차산로7나길 18 1101/1102호")).isEqualTo("서울 성동구 아차산로7나길 18");
        assertThat(AddressQuery.of("서울시 서초구 서초중앙로41 대성빌딩 7층")).isEqualTo("서울 서초구 서초중앙로 41");
        assertThat(AddressQuery.of("서울시 강남구 논현로 651 법무사회관 1, 5, 6층")).isEqualTo("서울 강남구 논현로 651");
        assertThat(AddressQuery.of("서울시 금천구 가산디지털2로 143, 15층")).isEqualTo("서울 금천구 가산디지털2로 143");
        assertThat(AddressQuery.of("서울시 강남구 삼성로 342")).isEqualTo("서울 강남구 삼성로 342");
        assertThat(AddressQuery.of("서울 종로구 종로 1")).isEqualTo("서울 종로구 종로 1"); // 구 이름 안의 '로'에 걸리지 않는다
        assertThat(AddressQuery.of("서울 중구 세종대로 110-1, 3층")).isEqualTo("서울 중구 세종대로 110-1");
    }

    @Test
    void 도로명을_못_찾으면_첫_쉼표_앞까지() {
        assertThat(AddressQuery.of("서울 성동구 (가상), 3층")).isEqualTo("서울 성동구 (가상)");
        assertThat(AddressQuery.beforeComma("서울시 강남구 언주로 537, ABT타워")).isEqualTo("서울시 강남구 언주로 537");
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

    private static KakaoLocalClient.Result geo(int status, String body) {
        return KakaoLocalClient.parse(status, body.getBytes(StandardCharsets.UTF_8));
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
