package kr.ac.skuniv.coopradar.me;

import jakarta.validation.Valid;
import kr.ac.skuniv.coopradar.auth.AuthUser;
import kr.ac.skuniv.coopradar.auth.RequireRole;
import kr.ac.skuniv.coopradar.auth.Role;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * docs/api #8 저장한 프로필 · #9 프로필 저장(동의 필수) · #10 프로필만 삭제. 학생만.
 * 저장하지 않아도 판정·추천은 요청 본문의 프로필로 쓸 수 있다.
 */
@RestController
@RequestMapping("/api/me/profile")
@RequireRole(Role.STUDENT)
public class ProfileController {

    private final ProfileService profiles;

    public ProfileController(ProfileService profiles) {
        this.profiles = profiles;
    }

    @GetMapping
    public SavedProfile get(AuthUser user) {
        return profiles.get(user);
    }

    @PutMapping
    public SavedProfile save(AuthUser user, @Valid @RequestBody ProfileSaveRequest body) {
        return profiles.save(user, body);
    }

    /** 계정과 담은 지망은 남는다. 저장한 게 없어도 204. */
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(AuthUser user) {
        profiles.delete(user);
    }
}
