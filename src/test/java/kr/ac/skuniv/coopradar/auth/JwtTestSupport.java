package kr.ac.skuniv.coopradar.auth;

import com.nimbusds.jwt.SignedJWT;
import java.text.ParseException;

final class JwtTestSupport {

    private JwtTestSupport() {
    }

    static long subject(String token) throws ParseException {
        return Long.parseLong(SignedJWT.parse(token).getJWTClaimsSet().getSubject());
    }
}
