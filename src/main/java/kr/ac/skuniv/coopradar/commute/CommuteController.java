package kr.ac.skuniv.coopradar.commute;

import jakarta.validation.Valid;
import kr.ac.skuniv.coopradar.auth.AuthUser;
import kr.ac.skuniv.coopradar.commute.CommuteDtos.CommuteRequest;
import kr.ac.skuniv.coopradar.commute.CommuteDtos.CommuteResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * docs/api #18 통근 시간. 로그인한 사람이면 역할과 상관없다.
 * 사는 곳은 URL이 아니라 본문으로 받는다(그래서 POST). 본문이 없거나 homeAreaCode가 null이면 서경대에서 출발한다.
 */
@RestController
public class CommuteController {

    private final CommuteService commute;

    public CommuteController(CommuteService commute) {
        this.commute = commute;
    }

    @PostMapping("/api/jobs/{jobId}/commute")
    public CommuteResponse commute(@PathVariable long jobId, @Valid @RequestBody(required = false) CommuteRequest body,
                                   AuthUser user) {
        return commute.commute(user, jobId, body == null ? null : body.homeAreaCode());
    }
}
