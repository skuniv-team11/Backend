package kr.ac.skuniv.coopradar.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * 호출 제한에 쓰는 클라이언트 IP. 메모리에서만 쓰고 저장·로그하지 않는다.
 * Render(*.onrender.com)는 Cloudflare를 거쳐 들어오므로 기본은 Cloudflare가 매 요청 덮어쓰는 CF-Connecting-IP다.
 * X-Forwarded-For의 마지막 값은 Cloudflare 엣지 주소일 수 있어 쓰지 않는다(ADR-0013, 배포 뒤 확인).
 */
@Component
public class ClientIp {

    private final String header;

    public ClientIp(AuthProperties props) {
        this.header = props.clientIpHeader();
    }

    public String of(HttpServletRequest request) {
        if (header != null && !header.isBlank()) {
            String value = request.getHeader(header);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return request.getRemoteAddr();
    }
}
