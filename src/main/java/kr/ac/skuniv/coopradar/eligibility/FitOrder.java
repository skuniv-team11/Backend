package kr.ac.skuniv.coopradar.eligibility;

import java.util.List;
import kr.ac.skuniv.coopradar.eligibility.EligibilityService.Judged;

/**
 * 판정 목록(#14)의 같은 판정 안 순서에 쓰는 적합도 순위(ADR-0027). 판정은 점수를 모르므로 구현은 적합도 쪽
 * ({@code recommend.FitListOrder})이 한다 — 추천(#15)과 같은 점수·순서다.
 */
public interface FitOrder {

    /**
     * 지원 불가가 아닌 직무 id를 적합도 순으로: 추천할 이유가 있는 직무 먼저, 그 안에서 높음 → 점수 → 리스트 순번(추천과 같음).
     * 지원 불가 직무는 넣지 않는다.
     */
    List<Integer> rank(int roundId, List<Judged> judged, ProfileInput profile);
}
