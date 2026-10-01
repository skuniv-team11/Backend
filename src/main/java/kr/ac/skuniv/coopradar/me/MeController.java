package kr.ac.skuniv.coopradar.me;

import kr.ac.skuniv.coopradar.auth.AuthDtos.UserView;
import kr.ac.skuniv.coopradar.auth.AuthUser;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** docs/api #6 내 계정 · #7 탈퇴. 로그인한 사람이면 역할과 상관없다. */
@RestController
@RequestMapping("/api/me")
public class MeController {

    private final MeService me;

    public MeController(MeService me) {
        this.me = me;
    }

    @GetMapping
    public UserView get(AuthUser user) {
        return me.account(user);
    }

    /** 계정·프로필·담은 지망을 바로 지운다(DB cascade, ADR-0008). */
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(AuthUser user) {
        me.delete(user);
    }
}
