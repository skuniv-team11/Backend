package kr.ac.skuniv.coopradar.eligibility;

import jakarta.validation.Valid;
import kr.ac.skuniv.coopradar.auth.RequireRole;
import kr.ac.skuniv.coopradar.auth.Role;
import kr.ac.skuniv.coopradar.eligibility.EligibilityDtos.Eligibility;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * docs/api #14 회차 직무 전부의 3층 판정. 학생만.
 * 프로필은 URL이 아니라 본문으로 받는다(그래서 POST). 저장한 프로필이 없어도 된다.
 */
@RestController
@RequireRole(Role.STUDENT)
public class EligibilityController {

    private final EligibilityService eligibility;

    public EligibilityController(EligibilityService eligibility) {
        this.eligibility = eligibility;
    }

    @PostMapping("/api/eligibility")
    public Eligibility check(@Valid @RequestBody ProfileBody body) {
        return eligibility.check(body.profile());
    }
}
