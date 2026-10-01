package kr.ac.skuniv.coopradar.commute;

import java.math.BigDecimal;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 통근 조회 설정(application.yml {@code app.commute}, ADR-0007).
 *
 * @param kakaoUrl       카카오 API 주소(테스트에서만 바꾼다)
 * @param kakaoKey       카카오 디벨로퍼스 REST API 키(환경변수 KAKAO_REST_API_KEY). 비면 카카오를 부르지 않고 PROVIDER_ERROR로 답한다
 * @param timeout        카카오 호출 전체 제한 시간(3초). 넘기면 PROVIDER_ERROR
 * @param perUserPerHour 계정당 1시간 한도(30). 넘으면 LIMITED. 0이면 제한 없음
 * @param perDay         서버 전체 하루 한도(900, 카카오 무료 하루 1,000건 안). 한국 시간 자정에 새로 센다. 0이면 제한 없음
 * @param school         사는 곳이 없을 때 출발점(서경대) 좌표(WGS84)
 */
@ConfigurationProperties("app.commute")
public record CommuteProperties(
        String kakaoUrl,
        String kakaoKey,
        Duration timeout,
        int perUserPerHour,
        int perDay,
        Point school) {

    /** WGS84 좌표. 카카오에는 x = 경도, y = 위도로 보낸다. */
    public record Point(BigDecimal lat, BigDecimal lng) {
    }
}
