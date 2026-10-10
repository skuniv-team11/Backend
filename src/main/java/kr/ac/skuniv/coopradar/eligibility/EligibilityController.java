package kr.ac.skuniv.coopradar.eligibility;

import jakarta.validation.Valid;
import kr.ac.skuniv.coopradar.auth.AuthUser;
import kr.ac.skuniv.coopradar.auth.RequireRole;
import kr.ac.skuniv.coopradar.auth.Role;
import kr.ac.skuniv.coopradar.eligibility.EligibilityDtos.Eligibility;
import kr.ac.skuniv.coopradar.me.ProfileService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * docs/api #14 회차 직무 전부의 3층 판정. 학생만.
 * 프로필은 URL이 아니라 본문으로 받는다(그래서 POST). 본문에 없으면 저장한 프로필로 본다(ADR-0035).
 */
@RestController
@RequireRole(Role.STUDENT)
public class EligibilityController {

    private final EligibilityService eligibility;
    private final ProfileService profiles;

    public EligibilityController(EligibilityService eligibility, ProfileService profiles) {
        this.eligibility = eligibility;
        this.profiles = profiles;
    }

    @PostMapping("/api/eligibility")
    public Eligibility check(AuthUser user, @Valid @RequestBody OptionalProfileBody body) {
        return eligibility.check(user.id(), profiles.bodyOrSaved(user, body.profile()));
    }
}
