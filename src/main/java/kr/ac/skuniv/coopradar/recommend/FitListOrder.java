package kr.ac.skuniv.coopradar.recommend;

import java.util.List;
import java.util.stream.Stream;
import kr.ac.skuniv.coopradar.eligibility.EligibilityService.Judged;
import kr.ac.skuniv.coopradar.eligibility.FitOrder;
import kr.ac.skuniv.coopradar.eligibility.ProfileInput;
import kr.ac.skuniv.coopradar.recommend.FitScorer.Scored;
import org.springframework.stereotype.Component;

/**
 * 판정 목록의 적합도 순위(ADR-0027): 추천할 이유가 있는 직무를 추천과 같은 {@link FitScorer} 순서로 먼저, 그다음 이유가 없는
 * 직무를 같은 순서로. 그래서 목록 맨 위가 추천 카드와 같은 순서로 이어지고, 점수가 높아도 이유가 없는 직무가 추천 직무 사이에
 * 끼지 않는다. {@link RecommendService}가 아니라 저장소를 직접 써서 판정 → 추천 → 판정으로 빈이 돌지 않게 한다.
 */
@Component
class FitListOrder implements FitOrder {

    private final RecommendRepository repository;

    FitListOrder(RecommendRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<Integer> rank(int roundId, List<Judged> judged, ProfileInput profile) {
        List<Scored> scored = FitScorer.score(judged, repository.features(roundId), profile.interestText(),
                profile.departmentId());
        return Stream.concat(scored.stream().filter(Scored::relevant), scored.stream().filter(s -> !s.relevant()))
                .map(Scored::jobId).toList();
    }
}
