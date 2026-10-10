package kr.ac.skuniv.coopradar.me;

import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import kr.ac.skuniv.coopradar.auth.AuthUser;
import kr.ac.skuniv.coopradar.common.ApiException;
import kr.ac.skuniv.coopradar.common.ErrorCode;
import kr.ac.skuniv.coopradar.eligibility.ProfileInput;
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

    /** 저장한 프로필을 판정 입력 모양으로(직무 탐색 결과를 다시 판정할 때, ADR-0031). 없으면 빈 값. */
    @Transactional(readOnly = true)
    public Optional<ProfileInput> savedInput(AuthUser user) {
        return profiles.findSaved(user.id(), user.guest()).map(p -> new ProfileInput(p.departmentId(), p.grade(),
                p.completedSemesters(), p.gpa(), p.graduationExpected(), p.interestText(), p.homeAreaCode(),
                p.certificates()));
    }

    /**
     * 판정에 쓸 프로필: 본문에 있으면 그것(저장하지 않고 써 보기), 없으면 저장한 프로필(ADR-0035).
     * 둘 다 없으면 404 PROFILE_NOT_FOUND.
     */
    @Transactional(readOnly = true)
    public ProfileInput bodyOrSaved(AuthUser user, ProfileInput body) {
        if (body != null) {
            return body;
        }
        return savedInput(user).orElseThrow(() -> new ApiException(ErrorCode.PROFILE_NOT_FOUND,
                "프로필을 보내거나 먼저 저장해 주세요"));
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
        profiles.upsert(user.id(), request, certificates(request.certificates()), clock.instant());
        return profiles.findSaved(user.id(), user.guest()).orElseThrow();
    }

    /** 코드표에 있는 코드만, 코드표 순서로 중복 없이(ADR-0021). 없는 코드가 있으면 400 INVALID_INPUT(certificates). */
    private List<String> certificates(List<String> codes) {
        if (codes == null) {
            return null;
        }
        if (codes.stream().anyMatch(Objects::isNull)) {
            throw ApiException.invalid("certificates", "GET /api/certificates의 code");
        }
        List<String> known = profiles.knownCertificates(codes);
        if (known.size() != new HashSet<>(codes).size()) {
            throw ApiException.invalid("certificates", "GET /api/certificates의 code");
        }
        return known;
    }

    @Transactional
    public void delete(AuthUser user) {
        profiles.delete(user.id());
    }
}
