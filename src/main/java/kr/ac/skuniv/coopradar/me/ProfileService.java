package kr.ac.skuniv.coopradar.me;

import java.time.Clock;
import kr.ac.skuniv.coopradar.auth.AuthUser;
import kr.ac.skuniv.coopradar.common.ApiException;
import kr.ac.skuniv.coopradar.common.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 저장한 프로필 조회·저장·삭제(#8~#10, ADR-0008). 저장은 동의(consent: true)했을 때만 한다.
 * 체험 계정의 프로필은 isExample: true로 돌려준다. 프로필 값은 로그에 남기지 않는다.
 */
@Service
public class ProfileService {

    private final ProfileRepository profiles;
    private final Clock clock;

    public ProfileService(ProfileRepository profiles, Clock clock) {
        this.profiles = profiles;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public SavedProfile get(AuthUser user) {
        return profiles.findSaved(user.id(), user.guest())
                .orElseThrow(() -> new ApiException(ErrorCode.PROFILE_NOT_FOUND, "저장한 프로필이 없어요"));
    }

    @Transactional
    public SavedProfile save(AuthUser user, ProfileSaveRequest request) {
        if (!Boolean.TRUE.equals(request.consent())) {
            throw new ApiException(ErrorCode.CONSENT_REQUIRED, "프로필을 저장하려면 수집·이용에 동의해 주세요");
        }
        if (!profiles.departmentExists(request.departmentId())) {
            throw ApiException.invalid("departmentId", "GET /api/departments의 id");
        }
        if (request.homeAreaCode() != null && !profiles.areaExists(request.homeAreaCode())) {
            throw ApiException.invalid("homeAreaCode", "GET /api/areas의 code");
        }
        profiles.upsert(user.id(), request, clock.instant());
        return profiles.findSaved(user.id(), user.guest()).orElseThrow();
    }

    @Transactional
    public void delete(AuthUser user) {
        profiles.delete(user.id());
    }
}
