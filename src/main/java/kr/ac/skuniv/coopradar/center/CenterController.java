package kr.ac.skuniv.coopradar.center;

import kr.ac.skuniv.coopradar.auth.AuthUser;
import kr.ac.skuniv.coopradar.auth.RequireRole;
import kr.ac.skuniv.coopradar.auth.Role;
import kr.ac.skuniv.coopradar.center.CenterDtos.CenterBoard;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** docs/api #24 센터 현황판. CENTER만(학생은 403 FORBIDDEN_ROLE). */
@RestController
@RequireRole(Role.CENTER)
public class CenterController {

    private final CenterService center;

    public CenterController(CenterService center) {
        this.center = center;
    }

    @GetMapping("/api/center/board")
    public CenterBoard board(AuthUser user) {
        return center.board(user);
    }
}
