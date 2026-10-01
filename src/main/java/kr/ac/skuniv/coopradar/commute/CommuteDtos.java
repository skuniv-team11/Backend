package kr.ac.skuniv.coopradar.commute;

import jakarta.validation.constraints.Pattern;
import java.time.OffsetDateTime;

/** 통근 조회의 요청·응답 모양(docs/api: commute.request.json, commute.json, commute-unavailable.json). 필드 이름이 JSON 이름이다. */
public final class CommuteDtos {

    private CommuteDtos() {
    }

    /** 사는 곳(시·군·구 5자리). null이거나 빠지면 서경대에서 출발한다. */
    public record CommuteRequest(
            @Pattern(regexp = "^(11|28|41)[0-9]{3}$", message = "시·군·구 코드 5자리(11·28·41로 시작)") String homeAreaCode) {
    }

    public enum OriginType { HOME_AREA, SCHOOL }

    public enum Provider { KAKAO_MAP }

    public enum UnavailableReason {
        /** 근무지 좌표 없음. 카카오를 부르지 않는다 */
        NO_WORKPLACE,
        /** 카카오가 경로를 못 찾음(NO_RESULTS·EQUAL_POINTS·STARTNODES_NULL·ENDNODES_NULL) */
        NO_ROUTE,
        /** 호출 제한·하루 한도(서버 한도 또는 카카오 쿼터) */
        LIMITED,
        /** 그 밖의 카카오 오류·제한 시간 초과·키 없음 */
        PROVIDER_ERROR
    }

    public record Origin(OriginType type, String areaCode, String label) {
    }

    public record Destination(String address) {
    }

    /** 실패해도 200. available이 false면 minutes·transfers·fareWon은 null이다. */
    public record CommuteResponse(
            long jobId,
            boolean available,
            Origin origin,
            Destination destination,
            Integer minutes,
            Integer transfers,
            Integer fareWon,
            Provider provider,
            OffsetDateTime queriedAt,
            UnavailableReason unavailableReason) {
    }
}
