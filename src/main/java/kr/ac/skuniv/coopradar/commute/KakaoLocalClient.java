package kr.ac.skuniv.coopradar.commute;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import kr.ac.skuniv.coopradar.commute.CommuteProperties.Point;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 카카오 로컬 주소 검색({@code GET /v2/local/search/address.json}). 통근 조회 때마다 출발(사는 곳 시·군·구)·도착(근로지 주소)
 * 좌표를 구하고 바로 버린다. 결과는 DB·캐시·시드 어디에도 두지 않는다(ADR-0007, 카카오 운영정책).
 * 검색어(주소)와 좌표는 로그에 남기지 않는다(오류 종류와 HTTP 상태만).
 * 응답 형식: https://developers.kakao.com/docs/ko/kakaomap/rest-api (documents[].x 경도 · y 위도, 문자열)
 */
@Component
public class KakaoLocalClient {

    private static final Logger log = LoggerFactory.getLogger(KakaoLocalClient.class);
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    /** 카카오 공통 오류 코드: API 사용 한도 초과 */
    private static final int QUOTA_EXCEEDED = -10;
    // 국내 좌표 범위(이전 V1 CHECK와 같은 값). 벗어나면 쓰지 않는다
    private static final BigDecimal LAT_MIN = BigDecimal.valueOf(33), LAT_MAX = BigDecimal.valueOf(39);
    private static final BigDecimal LNG_MIN = BigDecimal.valueOf(124), LNG_MAX = BigDecimal.valueOf(132);

    private final HttpClient http;
    private final String baseUrl;
    private final String key;
    private final Duration timeout;

    public KakaoLocalClient(CommuteProperties props) {
        this.timeout = props.timeout();
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
        this.baseUrl = props.kakaoUrl();
        this.key = props.kakaoKey() == null ? "" : props.kakaoKey().strip();
    }

    public enum Outcome { OK, NOT_FOUND, LIMITED, ERROR }

    /** OK면 첫 결과의 좌표. 아니면 point는 null. */
    public record Result(Outcome outcome, Point point) {

        static Result of(Outcome outcome) {
            return new Result(outcome, null);
        }
    }

    /** 주소 하나를 좌표로. 제한 시간 안에 끝나지 않거나 연결이 실패하면 ERROR. 예외를 던지지 않는다. */
    public CompletableFuture<Result> geocode(String query) {
        URI uri = UriComponentsBuilder.fromUriString(baseUrl)
                .path("/v2/local/search/address.json")
                .queryParam("query", query)
                .queryParam("size", 1)
                .build().encode().toUri();
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Authorization", "KakaoAK " + key)
                .header("Accept", "application/json")
                .GET()
                .build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                // 연결부터 본문까지 합쳐서 timeout 안에 끝나야 한다
                .orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                .thenApply(response -> parse(response.statusCode(), response.body()))
                .exceptionally(e -> {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    if (cause instanceof TimeoutException) {
                        log.warn("카카오 주소 검색이 {}ms를 넘겨 포기했습니다", timeout.toMillis());
                    } else {
                        log.warn("카카오 주소 검색 실패: {}", cause.getClass().getSimpleName());
                    }
                    return Result.of(Outcome.ERROR);
                });
    }

    /** 응답 해석. 결과가 있으면 documents[0]의 x(경도)·y(위도). */
    static Result parse(int status, byte[] body) {
        JsonNode json = null;
        try {
            json = body == null || body.length == 0 ? null : JSON.readTree(body);
        } catch (JacksonException e) {
            // 아래에서 오류로 처리
        }
        if (status == 429 || (json != null && json.path("code").asInt(0) == QUOTA_EXCEEDED)) {
            log.warn("카카오 주소 검색 한도 초과 응답: HTTP {}", status);
            return Result.of(Outcome.LIMITED);
        }
        if (status != 200 || json == null || !json.isObject() || !json.path("documents").isArray()) {
            log.warn("카카오 주소 검색 오류 응답: HTTP {} {}", status, json == null ? "" : json.path("errorType").asString(""));
            return Result.of(Outcome.ERROR);
        }
        JsonNode first = json.path("documents").path(0);
        if (first.isMissingNode()) {
            return Result.of(Outcome.NOT_FOUND);
        }
        try {
            BigDecimal lng = new BigDecimal(first.path("x").asString(""));
            BigDecimal lat = new BigDecimal(first.path("y").asString(""));
            if (lat.compareTo(LAT_MIN) < 0 || lat.compareTo(LAT_MAX) > 0
                    || lng.compareTo(LNG_MIN) < 0 || lng.compareTo(LNG_MAX) > 0) {
                return Result.of(Outcome.NOT_FOUND);
            }
            return new Result(Outcome.OK, new Point(lat, lng));
        } catch (NumberFormatException e) {
            log.warn("카카오 주소 검색 응답의 좌표를 읽을 수 없습니다");
            return Result.of(Outcome.ERROR);
        }
    }
}
