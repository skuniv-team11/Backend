package kr.ac.skuniv.coopradar.explore;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import kr.ac.skuniv.coopradar.auth.AuthUser;
import kr.ac.skuniv.coopradar.auth.ClientIp;
import kr.ac.skuniv.coopradar.auth.RequireRole;
import kr.ac.skuniv.coopradar.auth.Role;
import kr.ac.skuniv.coopradar.eligibility.ProfileBody;
import kr.ac.skuniv.coopradar.explore.ExploreDtos.Cards;
import kr.ac.skuniv.coopradar.explore.ExploreDtos.Explore;
import kr.ac.skuniv.coopradar.explore.ExploreDtos.JobWhy;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * docs/api #28~#32 직무 탐색(ADR-0031). 학생만. 프로필은 본문으로만 받는다. AI가 실패하거나 한도에 걸려도
 * 200 + 규칙 추천(source RULE)이고, '왜 맞나요'는 200 + why null이다.
 */
@RestController
@RequireRole(Role.STUDENT)
public class ExploreController {

    private final ExploreService explore;
    private final ClientIp clientIp;

    public ExploreController(ExploreService explore, ClientIp clientIp) {
        this.explore = explore;
        this.clientIp = clientIp;
    }

    @PostMapping("/api/explore/cards")
    public Cards cards(@Valid @RequestBody ProfileBody body) {
        return explore.cards(body.profile());
    }

    @PostMapping("/api/explore")
    public Explore explore(@Valid @RequestBody ExploreRequest body, AuthUser user, HttpServletRequest request) {
        return explore.explore(user, clientIp.of(request), body);
    }

    @GetMapping("/api/me/explore")
    public Explore mine(AuthUser user) {
        return explore.mine(user);
    }

    @DeleteMapping("/api/me/explore")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(AuthUser user) {
        explore.delete(user);
    }

    @GetMapping("/api/me/explore/jobs/{jobId}/why")
    public JobWhy why(@PathVariable int jobId, AuthUser user, HttpServletRequest request) {
        return explore.why(user, clientIp.of(request), jobId);
    }
}
