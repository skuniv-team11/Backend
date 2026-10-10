package kr.ac.skuniv.coopradar.auth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.text.ParseException;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import kr.ac.skuniv.coopradar.common.ApiException;
import kr.ac.skuniv.coopradar.common.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 로그인 토큰. JWT HS256, 서명 키는 JWT_SECRET(ADR-0008·0012).
 * 토큰에는 계정 id와 만료만 믿고 쓴다. 역할·체험 여부는 매 요청 DB에서 다시 읽는다.
 */
@Component
public class JwtService {

    static final String ACCOUNT_STAMP = "acc";

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);
    private static final int MIN_SECRET_BYTES = 32;

    private final MACSigner signer;
    private final MACVerifier verifier;
    private final Clock clock;

    public JwtService(AuthProperties props, Clock clock) throws JOSEException {
        this.clock = clock;
        byte[] secret;
        if (props.jwtSecret() == null || props.jwtSecret().isBlank()) {
            secret = new byte[MIN_SECRET_BYTES];
            new SecureRandom().nextBytes(secret);
            log.warn("JWT_SECRET이 없어 임시 서명 키를 만들었습니다. 서버가 다시 뜨면 모든 로그인이 풀립니다(로컬 전용). "
                    + "배포 환경에는 반드시 넣으세요");
        } else {
            secret = props.jwtSecret().getBytes(StandardCharsets.UTF_8);
            if (secret.length < MIN_SECRET_BYTES) {
                throw new IllegalStateException("JWT_SECRET은 " + MIN_SECRET_BYTES + "바이트 이상이어야 합니다");
            }
        }
        this.signer = new MACSigner(secret);
        this.verifier = new MACVerifier(secret);
    }

    /**
     * 계정이 만들어진 시각(마이크로초, {@code app_user.created_at})을 함께 넣는다. DB를 다시 만들어 같은 id가 다른 사람에게
     * 가도 옛 토큰이 새 계정으로 들어가지 못하게 한다(ADR-0005 재생성 · 10/10 리뷰).
     */
    public String issue(long userId, Role role, boolean guest, Instant expiresAt, long accountStamp) {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(Long.toString(userId))
                .claim(ACCOUNT_STAMP, accountStamp)
                .issueTime(Date.from(clock.instant()))
                .expirationTime(Date.from(expiresAt))
                .claim("role", role.name())
                .claim("guest", guest)
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        try {
            jwt.sign(signer);
        } catch (JOSEException e) {
            throw new IllegalStateException("토큰 서명 실패", e);
        }
        return jwt.serialize();
    }

    /** 확인한 토큰의 계정 id와 계정 생성 표시. 옛 토큰에는 표시가 없다(null). */
    public record Verified(long userId, Long accountStamp) {
    }

    /** 서명·알고리즘·만료를 확인하고 계정 id를 돌려준다. 만료면 TOKEN_EXPIRED, 그 밖은 AUTH_REQUIRED. */
    public Verified verify(String token) {
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            if (!JWSAlgorithm.HS256.equals(jwt.getHeader().getAlgorithm()) || !jwt.verify(verifier)) {
                throw invalid();
            }
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            Date exp = claims.getExpirationTime();
            if (exp == null || claims.getSubject() == null) {
                throw invalid();
            }
            if (!clock.instant().isBefore(exp.toInstant())) {
                throw new ApiException(ErrorCode.TOKEN_EXPIRED, "로그인이 만료됐어요. 다시 로그인해 주세요");
            }
            return new Verified(Long.parseLong(claims.getSubject()), claims.getLongClaim(ACCOUNT_STAMP));
        } catch (ParseException | JOSEException | NumberFormatException e) {
            throw invalid();
        }
    }

    private static ApiException invalid() {
        return new ApiException(ErrorCode.AUTH_REQUIRED, "로그인이 필요해요");
    }
}
