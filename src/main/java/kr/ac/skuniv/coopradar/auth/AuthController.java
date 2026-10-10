package kr.ac.skuniv.coopradar.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import kr.ac.skuniv.coopradar.auth.AuthDtos.CredentialsRequest;
import kr.ac.skuniv.coopradar.auth.AuthDtos.GuestRequest;
import kr.ac.skuniv.coopradar.auth.AuthDtos.GuestTokenResponse;
import kr.ac.skuniv.coopradar.auth.AuthDtos.LoginRequest;
import kr.ac.skuniv.coopradar.auth.AuthDtos.TokenResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** docs/api #3 가입 · #4 로그인 · #5 체험 계정. 로그아웃은 프론트가 토큰을 버리는 것으로 끝(서버 호출 없음). */
@RestController
@RequestMapping("/api/auth")
@PublicApi
public class AuthController {

    private final AuthService auth;
    private final ClientIp clientIp;

    public AuthController(AuthService auth, ClientIp clientIp) {
        this.auth = auth;
        this.clientIp = clientIp;
    }

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public TokenResponse signup(@Valid @RequestBody CredentialsRequest body) {
        return auth.signup(body.email(), body.password());
    }

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest body) {
        return auth.login(body.email(), body.password());
    }

    /** [예시 프로필로 시작](STUDENT) · [센터 담당자로 보기](CENTER). 누를 때마다 새 계정, 24시간 뒤 계정째 삭제. */
    @PostMapping("/guest")
    @ResponseStatus(HttpStatus.CREATED)
    public GuestTokenResponse guest(@Valid @RequestBody GuestRequest body, HttpServletRequest request) {
        return auth.guest(body.role(), body.stage(), body.demoGroup(), clientIp.of(request));
    }
}
