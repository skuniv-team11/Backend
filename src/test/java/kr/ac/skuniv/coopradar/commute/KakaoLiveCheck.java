package kr.ac.skuniv.coopradar.commute;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import kr.ac.skuniv.coopradar.common.Times;
import kr.ac.skuniv.coopradar.commute.CommuteProperties.Point;
import kr.ac.skuniv.coopradar.commute.KakaoTransitClient.Outcome;
import kr.ac.skuniv.coopradar.commute.KakaoTransitClient.Result;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 카카오 실제 호출 확인(키를 받은 뒤 수동으로 돌린다. CI·평소 테스트에서는 건너뛴다).
 * 서경대 → 서울시청을 2번 부르고(카카오 대중교통 무료 하루 1,000건 중 2건), 주소 검색을 3번 부른다.
 * 결과는 콘솔에만 찍고 어디에도 저장하지 않는다(ADR-0007).
 * 확인하는 것
 * 1) 서버 코드(KakaoTransitClient)가 실제 키·응답으로 OK를 받는지
 * 2) routes[0]이 가장 빠른 경로인지(공식 문서에 정렬 기준이 없음. docs/api는 첫 경로를 쓴다)
 * 3) 낮·밤에 한 번씩 돌려 소요 시간이 조회 시각에 따라 바뀌는지(카카오 API에 출발 시각 값이 없음)
 * 4) 주소 검색(KakaoLocalClient)이 사는 곳 시·군·구와 정리한 근로지 주소에서 좌표를 찾는지
 *
 * <pre>
 * PowerShell:  $env:KAKAO_LIVE_CHECK="1"; .\gradlew.bat test --tests "*KakaoLiveCheck*" -i
 * bash:        KAKAO_LIVE_CHECK=1 ./gradlew test --tests '*KakaoLiveCheck*' -i
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "KAKAO_LIVE_CHECK", matches = "1")
@EnabledIfEnvironmentVariable(named = "KAKAO_REST_API_KEY", matches = ".+")
class KakaoLiveCheck {

    // application.yml app.commute.school과 같은 값
    private static final Point SCHOOL = new Point(new BigDecimal("37.615"), new BigDecimal("127.0131"));
    // 서울시청(서울 중구 세종대로 110). 위키백과 Seoul City Hall 문서 좌표 37°33′59″N 126°58′40″E
    private static final Point CITY_HALL = new Point(new BigDecimal("37.5664"), new BigDecimal("126.9778"));

    @Test
    void 서경대에서_서울시청까지_실제_호출() throws Exception {
        String key = System.getenv("KAKAO_REST_API_KEY");
        KakaoTransitClient client = new KakaoTransitClient(new CommuteProperties(
                "https://dapi.kakao.com", key, Duration.ofSeconds(3), 0, 0, SCHOOL));

        // 1) 서비스와 같은 코드 경로
        Result r = client.route(SCHOOL, CITY_HALL);
        System.out.printf("[카카오 확인] 조회 %s KST · 결과 %s · %s분 · 환승 %s · 요금 %s원%n",
                ZonedDateTime.now(Times.KST).toLocalTime().withNano(0), r.outcome(),
                r.totalSeconds() == null ? "-" : CommuteService.minutes(r.totalSeconds()), r.transfers(), r.fareWon());
        assertThat(r.outcome()).as("OK가 아니면 위 경고 로그(HTTP 상태·errorType)를 보세요. 401이면 키, 403이면 카카오맵 활성화").isEqualTo(Outcome.OK);

        // 2) 경로 정렬 확인용 원문 1번 더
        URI uri = URI.create("https://dapi.kakao.com/v2/routing/publictraffic?start_x=" + SCHOOL.lng() + "&start_y=" + SCHOOL.lat()
                + "&end_x=" + CITY_HALL.lng() + "&end_y=" + CITY_HALL.lat());
        HttpResponse<String> raw = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(uri).header("Authorization", "KakaoAK " + key).timeout(Duration.ofSeconds(3)).build(),
                HttpResponse.BodyHandlers.ofString());
        JsonNode routes = JsonMapper.builder().build().readTree(raw.body()).path("routes");
        List<Integer> minutes = new ArrayList<>();
        for (JsonNode route : routes) {
            minutes.add(CommuteService.minutes(route.path("properties").path("totalTime").asInt()));
        }
        boolean firstIsFastest = minutes.stream().allMatch(m -> m >= minutes.get(0));
        System.out.printf("[카카오 확인] 경로 %d개 소요(분) %s · 첫 경로가 가장 빠름: %s%n", minutes.size(), minutes, firstIsFastest);
    }

    @Test
    void 사는_곳과_근로지_주소를_실제로_찾는다() {
        String key = System.getenv("KAKAO_REST_API_KEY");
        KakaoLocalClient client = new KakaoLocalClient(new CommuteProperties(
                "https://dapi.kakao.com", key, Duration.ofSeconds(3), 0, 0, SCHOOL));
        // 사는 곳(시·군·구)과 2026-2 근로지 주소 형태 하나(건물명·층이 붙은 원문 → 정리한 검색어)
        for (String query : List.of("서울 노원구", "경기 수원시 장안구",
                AddressQuery.of("서울시 성동구 뚝섬로1길 25, 706-707호"))) {
            KakaoLocalClient.Result r = client.geocode(query).join();
            System.out.printf("[카카오 확인] 주소 검색 '%s' → %s %s%n", query, r.outcome(), r.point());
            assertThat(r.outcome()).as(query).isEqualTo(KakaoLocalClient.Outcome.OK);
        }
    }
}
