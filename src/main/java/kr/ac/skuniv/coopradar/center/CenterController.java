package kr.ac.skuniv.coopradar.center;

import java.time.LocalDate;
import kr.ac.skuniv.coopradar.auth.AuthUser;
import kr.ac.skuniv.coopradar.auth.RequireRole;
import kr.ac.skuniv.coopradar.auth.Role;
import kr.ac.skuniv.coopradar.center.CenterDtos.CenterBoard;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * docs/api #24 센터 모집 현황판. CENTER만(학생은 403 FORBIDDEN_ROLE).
 * asOf(yyyy-MM-dd)를 생략하면 rounds/current의 replay.defaultAsOf, 모집기간 밖이면 400 AS_OF_OUT_OF_RANGE.
 */
@RestController
@RequireRole(Role.CENTER)
public class CenterController {

    private final CenterService center;

    public CenterController(CenterService center) {
        this.center = center;
    }

    @GetMapping("/api/center/board")
    public CenterBoard board(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
                             AuthUser user) {
        return center.board(user, asOf);
    }
}
