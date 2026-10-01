package kr.ac.skuniv.coopradar.me;

import kr.ac.skuniv.coopradar.auth.AuthDtos.UserView;
import kr.ac.skuniv.coopradar.auth.AuthUser;
import kr.ac.skuniv.coopradar.auth.UserRepository;
import kr.ac.skuniv.coopradar.common.ApiException;
import kr.ac.skuniv.coopradar.common.ErrorCode;
import org.springframework.stereotype.Service;

/** 내 계정 조회·탈퇴. */
@Service
public class MeService {

    private final UserRepository users;

    public MeService(UserRepository users) {
        this.users = users;
    }

    public UserView account(AuthUser user) {
        return users.findAccount(user.id()).map(UserView::of)
                .orElseThrow(() -> new ApiException(ErrorCode.AUTH_REQUIRED, "로그인이 필요해요"));
    }

    public void delete(AuthUser user) {
        users.delete(user.id());
    }
}
