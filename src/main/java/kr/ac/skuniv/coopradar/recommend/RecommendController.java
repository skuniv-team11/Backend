package kr.ac.skuniv.coopradar.recommend;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import kr.ac.skuniv.coopradar.auth.AuthUser;
import kr.ac.skuniv.coopradar.auth.ClientIp;
import kr.ac.skuniv.coopradar.auth.RequireRole;
import kr.ac.skuniv.coopradar.auth.Role;
import kr.ac.skuniv.coopradar.eligibility.ProfileBody;
import kr.ac.skuniv.coopradar.recommend.RecommendDtos.RecommendationReason;
import kr.ac.skuniv.coopradar.recommend.RecommendDtos.Recommendations;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * docs/api #15 적합도 추천 상위 5개 · #16 추천 이유 문장. 학생만. 프로필은 본문으로만 받는다(저장·로그 안 함).
 * #16은 LLM이 실패하거나 제한에 걸려도 200 + TEMPLATE다.
 */
@RestController
@RequireRole(Role.STUDENT)
public class RecommendController {

    private final RecommendService recommend;
    private final ReasonService reasons;
    private final ClientIp clientIp;

    public RecommendController(RecommendService recommend, ReasonService reasons, ClientIp clientIp) {
        this.recommend = recommend;
        this.reasons = reasons;
        this.clientIp = clientIp;
    }

    @PostMapping("/api/recommendations")
    public Recommendations recommend(@Valid @RequestBody ProfileBody body) {
        return recommend.recommend(body.profile());
    }

    @PostMapping("/api/recommendations/{jobId}/reason")
    public RecommendationReason reason(@PathVariable long jobId, @Valid @RequestBody ProfileBody body, AuthUser user,
                                       HttpServletRequest request) {
        return reasons.reason(user, clientIp.of(request), jobId, body.profile());
    }
}
