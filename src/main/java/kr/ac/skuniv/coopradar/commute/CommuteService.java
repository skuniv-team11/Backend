package kr.ac.skuniv.coopradar.commute;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.concurrent.CompletableFuture;
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
 * docs/api #18 통근 조회(ADR-0007). 사는 곳(없으면 서경대)에서 근로지까지 카카오만으로 그때 계산하고 버린다.
 * ① 카카오 주소 검색으로 출발(사는 곳 시·군·구)·도착(근로지 주소) 좌표를 동시에 구하고 ② 카카오 대중교통 길찾기를 1번 부른다.
 * 결과·좌표·사는 곳 코드는 저장하지도 로그에 남기지도 않는다. 카카오 쪽 실패는 모두 200 + available false다.
 */
@Service
public class CommuteService {

    static final String SCHOOL_LABEL = "서경대";
    private static final String NO_ADDRESS = "근무지 주소 없음";

    private final CommuteRepository repo;
    private final CommuteLimiter limiter;
    private final KakaoTransitClient kakao;
    private final KakaoLocalClient local;
    private final Point school;
    private final Clock clock;

    public CommuteService(CommuteRepository repo, CommuteLimiter limiter, KakaoTransitClient kakao,
                          KakaoLocalClient local, CommuteProperties props, Clock clock) {
        this.repo = repo;
        this.limiter = limiter;
        this.kakao = kakao;
        this.local = local;
        this.school = props.school();
        this.clock = clock;
    }

    public CommuteResponse commute(AuthUser user, long jobId, String homeAreaCode) {
        Origin origin;
        Area area = null;
        if (homeAreaCode == null) {
            origin = new Origin(OriginType.SCHOOL, null, SCHOOL_LABEL);
        } else {
            area = repo.findArea(homeAreaCode)
                    .orElseThrow(() -> ApiException.invalid("homeAreaCode", "사는 곳 목록에 없는 코드예요"));
            origin = new Origin(OriginType.HOME_AREA, area.code(), area.name());
        }
        JobPlace job = repo.findJobPlace(jobId)
                .orElseThrow(() -> new ApiException(ErrorCode.JOB_NOT_FOUND, "직무를 찾을 수 없어요"));
        Destination destination = new Destination(job.address() == null ? NO_ADDRESS : job.address());
        OffsetDateTime now = Times.kst(clock.instant());

        if (job.address() == null) {
            return unavailable(jobId, origin, destination, now, UnavailableReason.NO_WORKPLACE);
        }
        if (!kakao.configured()) {
            return unavailable(jobId, origin, destination, now, UnavailableReason.PROVIDER_ERROR);
        }
        if (!limiter.tryAcquire(user.id())) {
            return unavailable(jobId, origin, destination, now, UnavailableReason.LIMITED);
        }

        // ① 좌표: 출발·도착을 동시에 찾는다. 서경대는 설정값
        CompletableFuture<KakaoLocalClient.Result> fromCall = area == null
                ? CompletableFuture.completedFuture(new KakaoLocalClient.Result(KakaoLocalClient.Outcome.OK, school))
                : local.geocode(area.query());
        CompletableFuture<KakaoLocalClient.Result> toCall = workplace(job.address());
        KakaoLocalClient.Result from = fromCall.join();
        KakaoLocalClient.Result to = toCall.join();
        if (to.outcome() == KakaoLocalClient.Outcome.NOT_FOUND) {
            return unavailable(jobId, origin, destination, now, UnavailableReason.NO_WORKPLACE);
        }
        UnavailableReason failed = failure(from.outcome(), to.outcome());
        if (failed != null) {
            return unavailable(jobId, origin, destination, now, failed);
        }

        // ② 대중교통 길찾기
        Result r = kakao.route(from.point(), to.point());
        return switch (r.outcome()) {
            case OK -> new CommuteResponse(jobId, true, origin, destination, minutes(r.totalSeconds()), r.transfers(),
                    r.fareWon(), Provider.KAKAO_MAP, now, null);
            case NO_ROUTE -> unavailable(jobId, origin, destination, now, UnavailableReason.NO_ROUTE);
            case LIMITED -> unavailable(jobId, origin, destination, now, UnavailableReason.LIMITED);
            case ERROR -> unavailable(jobId, origin, destination, now, UnavailableReason.PROVIDER_ERROR);
        };
    }

    /** 근로지 주소 검색. 정리한 검색어로 못 찾으면 첫 쉼표 앞 원문으로 한 번 더(다르면). */
    private CompletableFuture<KakaoLocalClient.Result> workplace(String address) {
        String query = AddressQuery.of(address);
        String fallback = AddressQuery.beforeComma(address.strip().replaceAll("\\s+", " "));
        return local.geocode(query).thenCompose(r -> r.outcome() == KakaoLocalClient.Outcome.NOT_FOUND && !fallback.equals(query)
                ? local.geocode(fallback)
                : CompletableFuture.completedFuture(r));
    }

    /** 주소 검색 실패를 이유로 바꾼다. 둘 다 OK면 null. 사는 곳을 못 찾는 건 카카오 쪽 문제로 본다. */
    static UnavailableReason failure(KakaoLocalClient.Outcome from, KakaoLocalClient.Outcome to) {
        if (from == KakaoLocalClient.Outcome.LIMITED || to == KakaoLocalClient.Outcome.LIMITED) {
            return UnavailableReason.LIMITED;
        }
        if (from != KakaoLocalClient.Outcome.OK || to != KakaoLocalClient.Outcome.OK) {
            return UnavailableReason.PROVIDER_ERROR;
        }
        return null;
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
