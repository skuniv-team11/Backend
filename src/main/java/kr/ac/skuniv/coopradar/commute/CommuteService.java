package kr.ac.skuniv.coopradar.commute;

import java.time.Clock;
import java.time.OffsetDateTime;
import kr.ac.skuniv.coopradar.auth.AuthUser;
import kr.ac.skuniv.coopradar.common.ApiException;
import kr.ac.skuniv.coopradar.common.ErrorCode;
import kr.ac.skuniv.coopradar.common.Times;
import kr.ac.skuniv.coopradar.commute.CommuteDtos.CommuteResponse;
import kr.ac.skuniv.coopradar.commute.CommuteDtos.Destination;
import kr.ac.skuniv.coopradar.commute.CommuteDtos.Origin;
import kr.ac.skuniv.coopradar.commute.CommuteDtos.OriginType;
import kr.ac.skuniv.coopradar.commute.CommuteDtos.Provider;
import kr.ac.skuniv.coopradar.commute.CommuteDtos.UnavailableReason;
import kr.ac.skuniv.coopradar.commute.CommuteProperties.Point;
import kr.ac.skuniv.coopradar.commute.CommuteRepository.Area;
import kr.ac.skuniv.coopradar.commute.CommuteRepository.JobPlace;
import kr.ac.skuniv.coopradar.commute.KakaoTransitClient.Result;
import org.springframework.stereotype.Service;

/**
 * docs/api #18 통근 조회(ADR-0007). 사는 곳(없으면 서경대)에서 근로지까지 카카오 대중교통으로 그때 계산하고 버린다.
 * 결과·사는 곳 코드·좌표는 저장하지도 로그에 남기지도 않는다. 카카오 쪽 실패는 모두 200 + available false다.
 */
@Service
public class CommuteService {

    static final String SCHOOL_LABEL = "서경대";
    private static final String NO_ADDRESS = "근무지 주소 없음";

    private final CommuteRepository repo;
    private final CommuteLimiter limiter;
    private final KakaoTransitClient kakao;
    private final Point school;
    private final Clock clock;

    public CommuteService(CommuteRepository repo, CommuteLimiter limiter, KakaoTransitClient kakao,
                          CommuteProperties props, Clock clock) {
        this.repo = repo;
        this.limiter = limiter;
        this.kakao = kakao;
        this.school = props.school();
        this.clock = clock;
    }

    public CommuteResponse commute(AuthUser user, long jobId, String homeAreaCode) {
        Origin origin;
        Point from;
        if (homeAreaCode == null) {
            origin = new Origin(OriginType.SCHOOL, null, SCHOOL_LABEL);
            from = school;
        } else {
            Area area = repo.findArea(homeAreaCode)
                    .orElseThrow(() -> ApiException.invalid("homeAreaCode", "사는 곳 목록에 없는 코드예요"));
            origin = new Origin(OriginType.HOME_AREA, area.code(), area.name());
            from = area.point();
        }
        JobPlace job = repo.findJobPlace(jobId)
                .orElseThrow(() -> new ApiException(ErrorCode.JOB_NOT_FOUND, "직무를 찾을 수 없어요"));
        Destination destination = new Destination(job.address() == null ? NO_ADDRESS : job.address());
        OffsetDateTime now = Times.kst(clock.instant());

        if (job.point() == null) {
            return unavailable(jobId, origin, destination, now, UnavailableReason.NO_WORKPLACE);
        }
        if (!kakao.configured()) {
            return unavailable(jobId, origin, destination, now, UnavailableReason.PROVIDER_ERROR);
        }
        if (!limiter.tryAcquire(user.id())) {
            return unavailable(jobId, origin, destination, now, UnavailableReason.LIMITED);
        }
        Result r = kakao.route(from, job.point());
        return switch (r.outcome()) {
            case OK -> new CommuteResponse(jobId, true, origin, destination, minutes(r.totalSeconds()), r.transfers(),
                    r.fareWon(), Provider.KAKAO_MAP, now, null);
            case NO_ROUTE -> unavailable(jobId, origin, destination, now, UnavailableReason.NO_ROUTE);
            case LIMITED -> unavailable(jobId, origin, destination, now, UnavailableReason.LIMITED);
            case ERROR -> unavailable(jobId, origin, destination, now, UnavailableReason.PROVIDER_ERROR);
        };
    }

    /** totalTime(초) ÷ 60 반올림. 계약상 1분 이상이다. */
    static int minutes(int totalSeconds) {
        return (int) Math.max(1, Math.round(totalSeconds / 60.0));
    }

    private static CommuteResponse unavailable(long jobId, Origin origin, Destination destination, OffsetDateTime now,
                                               UnavailableReason reason) {
        return new CommuteResponse(jobId, false, origin, destination, null, null, null, Provider.KAKAO_MAP, now, reason);
    }
}
