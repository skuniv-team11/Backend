package kr.ac.skuniv.coopradar.auth;

import java.time.Instant;

/**
 * 요청한 사람. 컨트롤러 메서드 인자로 받는다({@code AuthUser me}). 역할은 토큰이 아니라 DB 값이다.
 *
 * @param expiresAt 체험 계정이 지워지는 시각. 가입 계정은 null
 */
public record AuthUser(long id, Role role, boolean guest, Instant expiresAt) {

    static final String ATTRIBUTE = AuthUser.class.getName();
}
