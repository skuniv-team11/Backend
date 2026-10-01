package kr.ac.skuniv.coopradar.commute;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
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
 * 카카오 대중교통 길찾기({@code GET /v2/routing/publictraffic}). 결과는 돌려주기만 하고 DB·캐시 어디에도 두지 않는다(ADR-0007).
 * 요청 주소에 좌표가 들어가므로 주소·예외 메시지는 로그에 남기지 않는다(오류 종류와 HTTP 상태만).
 * 응답 형식: https://developers.kakao.com/docs/ko/kakaomap/rest-api (totalTime 초, fare.value 원, x 경도 · y 위도)
 */
@Component
public class KakaoTransitClient {

    private static final Logger log = LoggerFactory.getLogger(KakaoTransitClient.class);
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final Set<String> NO_ROUTE = Set.of("NO_RESULTS", "EQUAL_POINTS", "STARTNODES_NULL", "ENDNODES_NULL");
    /** 카카오 공통 오류 코드: API 사용 한도 초과 */
    private static final int QUOTA_EXCEEDED = -10;

    private final HttpClient http;
    private final String baseUrl;
    private final String key;
    private final Duration timeout;

    public KakaoTransitClient(CommuteProperties props) {
        this.timeout = props.timeout();
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
        this.baseUrl = props.kakaoUrl();
        this.key = props.kakaoKey() == null ? "" : props.kakaoKey().strip();
        if (key.isEmpty()) {
            log.warn("KAKAO_REST_API_KEY가 없어 통근 조회는 카카오를 부르지 않고 PROVIDER_ERROR로 답합니다");
        }
    }

    public enum Outcome { OK, NO_ROUTE, LIMITED, ERROR }

    /** OK면 첫 경로의 값. 아니면 값은 null. */
    public record Result(Outcome outcome, Integer totalSeconds, Integer transfers, Integer fareWon) {

        static Result of(Outcome outcome) {
            return new Result(outcome, null, null, null);
        }
    }

    public boolean configured() {
        return !key.isEmpty();
    }

    public Result route(Point from, Point to) {
        URI uri = UriComponentsBuilder.fromUriString(baseUrl)
                .path("/v2/routing/publictraffic")
                .queryParam("start_x", from.lng().toPlainString())
                .queryParam("start_y", from.lat().toPlainString())
                .queryParam("end_x", to.lng().toPlainString())
                .queryParam("end_y", to.lat().toPlainString())
                .build().toUri();
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Authorization", "KakaoAK " + key)
                .header("Accept", "application/json")
                .GET()
                .build();
        CompletableFuture<HttpResponse<byte[]>> call = http.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray());
        try {
            // 연결부터 본문까지 합쳐서 timeout 안에 끝나야 한다
            HttpResponse<byte[]> response = call.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return parse(response.statusCode(), response.body());
        } catch (TimeoutException e) {
            call.cancel(true);
            log.warn("카카오 대중교통 호출이 {}ms를 넘겨 포기했습니다", timeout.toMillis());
            return Result.of(Outcome.ERROR);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            call.cancel(true);
            return Result.of(Outcome.ERROR);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.warn("카카오 대중교통 호출 실패: {}", cause.getClass().getSimpleName());
            return Result.of(Outcome.ERROR);
        }
    }

    /** 응답 해석. 상태 OK면 routes[0](docs/api 규칙: 첫 경로)의 값을 쓴다. */
    static Result parse(int status, byte[] body) {
        JsonNode json = null;
        try {
            json = body == null || body.length == 0 ? null : JSON.readTree(body);
        } catch (JacksonException e) {
            // 아래에서 오류로 처리
        }
        if (status == 429 || (json != null && json.path("code").asInt(0) == QUOTA_EXCEEDED)) {
            log.warn("카카오 대중교통 한도 초과 응답: HTTP {}", status);
            return Result.of(Outcome.LIMITED);
        }
        if (status != 200 || json == null || !json.isObject()) {
            log.warn("카카오 대중교통 오류 응답: HTTP {} {}", status, json == null ? "" : json.path("errorType").asString(""));
            return Result.of(Outcome.ERROR);
        }
        String kakaoStatus = json.path("status").asString("");
        if (NO_ROUTE.contains(kakaoStatus)) {
            return Result.of(Outcome.NO_ROUTE);
        }
        JsonNode first = json.path("routes").path(0).path("properties");
        if (!"OK".equals(kakaoStatus) || !first.path("totalTime").isIntegralNumber()) {
            log.warn("카카오 대중교통 응답을 쓸 수 없습니다: status {}", kakaoStatus);
            return Result.of(Outcome.ERROR);
        }
        return new Result(Outcome.OK,
                first.path("totalTime").asInt(),
                intOrNull(first.path("transfers")),
                intOrNull(first.path("fare").path("value")));
    }

    private static Integer intOrNull(JsonNode node) {
        return node.isIntegralNumber() ? node.asInt() : null;
    }
}
