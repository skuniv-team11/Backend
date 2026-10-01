package kr.ac.skuniv.coopradar.auth;

import jakarta.servlet.http.HttpServletRequest;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 호출 제한에 쓰는 클라이언트 IP. 메모리에서만 쓰고 저장·로그하지 않는다(ADR-0013).
 * Render(*.onrender.com)는 Cloudflare를 거쳐 들어오고, 요청에 CF-Connecting-IP(= True-Client-IP)가 실제 클라이언트로 붙는다.
 * X-Forwarded-For는 "클라이언트, Cloudflare, Render 내부" 순이라 마지막 값을 쓰면 모두가 한 IP로 묶인다.
 */
@Component
public class ClientIp {

    private static final Logger log = LoggerFactory.getLogger(ClientIp.class);

    private final String header;
    private final AtomicBoolean warned = new AtomicBoolean();

    public ClientIp(AuthProperties props) {
        this.header = props.clientIpHeader();
    }

    public String of(HttpServletRequest request) {
        if (header != null && !header.isBlank()) {
            String value = request.getHeader(header);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
            if (warned.compareAndSet(false, true)) {
                // 로컬에서는 정상. 배포에서 보이면 모든 사용자가 한 IP로 세어진다 → CLIENT_IP_HEADER 확인
                log.warn("{} 헤더가 없어 연결 주소로 호출 제한을 셉니다(이 경고는 한 번만). 배포 환경이면 CLIENT_IP_HEADER를 확인하세요",
                        header);
            }
        }
        return request.getRemoteAddr();
    }
}
