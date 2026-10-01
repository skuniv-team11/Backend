package kr.ac.skuniv.coopradar.auth;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import kr.ac.skuniv.coopradar.auth.AuthDtos.GuestTokenResponse;
import kr.ac.skuniv.coopradar.auth.AuthDtos.TokenResponse;
import kr.ac.skuniv.coopradar.auth.AuthDtos.UserView;
import kr.ac.skuniv.coopradar.common.ApiException;
import kr.ac.skuniv.coopradar.common.ErrorCode;
import kr.ac.skuniv.coopradar.common.Times;
import kr.ac.skuniv.coopradar.me.ExampleProfileProperties;
import kr.ac.skuniv.coopradar.me.ProfileRepository;
import kr.ac.skuniv.coopradar.me.ProfileView;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 가입·로그인·체험 계정(ADR-0008·0012). 필수로 받는 건 이메일과 비밀번호 해시뿐이다. */
@Service
public class AuthService {

    /** BCrypt 입력 한도. 넘는 부분을 조용히 자르지 않고 막는다. */
    static final int MAX_PASSWORD_BYTES = 72;
    private static final String TOKEN_TYPE = "Bearer";

    private final UserRepository users;
    private final ProfileRepository profiles;
    private final JwtService jwt;
    private final GuestRateLimiter limiter;
    private final AuthProperties props;
    private final ExampleProfileProperties example;
    private final Clock clock;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    /** 없는 이메일로 로그인해도 같은 시간이 걸리게 비교할 해시(계정 존재 여부를 응답 시간으로 흘리지 않음). */
    private final String dummyHash = encoder.encode("dummy-password-for-timing");

    public AuthService(UserRepository users, ProfileRepository profiles, JwtService jwt, GuestRateLimiter limiter,
                       AuthProperties props, ExampleProfileProperties example, Clock clock) {
        this.users = users;
        this.profiles = profiles;
        this.jwt = jwt;
        this.limiter = limiter;
        this.props = props;
        this.example = example;
        this.clock = clock;
    }

    @Transactional
    public TokenResponse signup(String email, String password) {
        checkPasswordBytes(password);
        String normalized = normalize(email);
        long id;
        try {
            id = users.insertMember(normalized, encoder.encode(password));
        } catch (DuplicateKeyException e) {
            throw new ApiException(ErrorCode.EMAIL_TAKEN, "이미 가입한 이메일이에요");
        }
        return memberToken(id);
    }

    @Transactional(readOnly = true)
    public TokenResponse login(String email, String password) {
        var found = users.findCredentials(normalize(email));
        boolean ok = encoder.matches(password, found.map(UserRepository.Credentials::passwordHash).orElse(dummyHash))
                && found.isPresent();
        if (!ok) {
            throw new ApiException(ErrorCode.LOGIN_FAILED, "이메일 또는 비밀번호가 맞지 않아요");
        }
        return memberToken(found.get().id());
    }

    @Transactional
    public GuestTokenResponse guest(Role role, String clientIp) {
        if (!limiter.tryAcquire(clientIp)) {
            throw new ApiException(ErrorCode.RATE_LIMITED, "잠시 뒤 다시 시도해 주세요");
        }
        Instant now = clock.instant();
        Instant expiresAt = now.plus(props.guestTtl());
        long id = users.insertGuest(role, expiresAt);
        ProfileView profile = null;
        if (role == Role.STUDENT) {
            int departmentId = profiles.findDepartmentId(example.departmentName()).orElseThrow(() -> new IllegalStateException(
                    "예시 프로필 학과 '" + example.departmentName() + "'가 department 시드에 없습니다"));
            String area = example.homeAreaCode() != null && profiles.areaExists(example.homeAreaCode())
                    ? example.homeAreaCode() : null;
            profiles.insertExample(id, departmentId, example, area, now);
            profile = profiles.findView(id, true).orElseThrow();
        }
        UserView user = UserView.of(users.findAccount(id).orElseThrow());
        return new GuestTokenResponse(jwt.issue(id, role, true, expiresAt), TOKEN_TYPE, Times.kst(expiresAt), user, profile);
    }

    private TokenResponse memberToken(long id) {
        Instant expiresAt = clock.instant().plus(props.memberTtl());
        UserView user = UserView.of(users.findAccount(id).orElseThrow());
        return new TokenResponse(jwt.issue(id, user.role(), false, expiresAt), TOKEN_TYPE, Times.kst(expiresAt), user);
    }

    private static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static void checkPasswordBytes(String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            throw ApiException.invalid("password", "72바이트 이하(영문 72자, 한글 24자)");
        }
    }
}
