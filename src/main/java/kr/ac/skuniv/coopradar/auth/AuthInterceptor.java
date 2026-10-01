package kr.ac.skuniv.coopradar.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import kr.ac.skuniv.coopradar.common.ApiException;
import kr.ac.skuniv.coopradar.common.ErrorCode;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * {@code /api/**}의 로그인·역할 확인(ADR-0012).
 * 기본은 로그인 필요. {@link PublicApi}가 붙은 것만 통과시키고, {@link RequireRole}이 있으면 역할도 본다.
 * 여기서 던진 예외는 컨트롤러 예외와 같이 ApiErrorHandler가 계약 형식으로 바꾼다.
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private static final String BEARER = "Bearer ";

    private final JwtService jwt;
    private final UserRepository users;
    private final Clock clock;

    public AuthInterceptor(JwtService jwt, UserRepository users, Clock clock) {
        this.jwt = jwt;
        this.users = users;
        this.clock = clock;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return true; // CORS 사전 요청·정적 파일
        }
        if (find(method, PublicApi.class) != null) {
            return true;
        }
        String header = request.getHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            throw new ApiException(ErrorCode.AUTH_REQUIRED, "로그인이 필요해요");
        }
        long userId = jwt.verify(header.substring(BEARER.length()).trim());
        AuthUser user = users.findAuthUser(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.AUTH_REQUIRED, "로그인이 필요해요")); // 탈퇴·정리된 계정
        if (user.guest() && user.expiresAt() != null && !clock.instant().isBefore(user.expiresAt())) {
            throw new ApiException(ErrorCode.TOKEN_EXPIRED, "체험 시간이 끝났어요. 다시 시작해 주세요");
        }
        RequireRole role = find(method, RequireRole.class);
        if (role != null && role.value() != user.role()) {
            throw new ApiException(ErrorCode.FORBIDDEN_ROLE, "이 화면을 볼 권한이 없어요");
        }
        request.setAttribute(AuthUser.ATTRIBUTE, user);
        return true;
    }

    static <A extends java.lang.annotation.Annotation> A find(HandlerMethod method, Class<A> type) {
        A onMethod = AnnotatedElementUtils.findMergedAnnotation(method.getMethod(), type);
        return onMethod != null ? onMethod : AnnotatedElementUtils.findMergedAnnotation(method.getBeanType(), type);
    }
}
