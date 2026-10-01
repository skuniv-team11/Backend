package kr.ac.skuniv.coopradar.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 로그인 설정(application.yml {@code app.auth}).
 *
 * @param jwtSecret         HS256 서명 키(환경변수 JWT_SECRET, 32바이트 이상). 비우면 서버가 뜰 때마다 임시 키를 만든다(로컬 전용)
 * @param memberTtl         가입 계정 토큰 유효 기간(7일)
 * @param guestTtl          체험 계정과 그 토큰의 수명(24시간). 지나면 계정째 지운다
 * @param guestPerIpPerHour 체험 계정 만들기 IP당 1시간 한도(환경변수 GUEST_PER_IP_PER_HOUR, 기본 300). 넘으면 429, 0이면 제한 없음
 * @param clientIpHeader    클라이언트 IP를 읽을 헤더. 비우면 연결 주소를 쓴다
 */
@ConfigurationProperties("app.auth")
public record AuthProperties(
        String jwtSecret,
        Duration memberTtl,
        Duration guestTtl,
        int guestPerIpPerHour,
        String clientIpHeader) {
}
