package kr.ac.skuniv.coopradar.explore;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import kr.ac.skuniv.coopradar.eligibility.ProfileInput;

/**
 * {@code POST /api/explore} 본문(계약 스키마 ExploreRequest, docs/api explore.request.json). 형식·범위는 여기서,
 * '경험 1개 이상 또는 카드 3개 이상'·카드가 있는지·동의는 {@link ExploreService}가 본다.
 *
 * @param experiences 해 본 일 0~3개(하나에 20~200자). null이면 없음
 * @param cardIds     고른 '하고 싶은 일' 카드 0~5개(#28의 id). null이면 없음
 * @param consent     경험 글을 AI에 보내고 결과와 함께 저장하는 데 동의. true가 아니면 400 CONSENT_REQUIRED
 */
public record ExploreRequest(
        @NotNull(message = "필수예요") @Valid ProfileInput profile,
        @Size(max = 3, message = "3개 이하")
        List<@NotNull(message = "필수예요") @Size(min = 20, max = 200, message = "20~200자") String> experiences,
        @Size(max = 5, message = "5개 이하")
        List<@NotNull(message = "필수예요") @Pattern(regexp = "^\\d+-\\d+$", message = "POST /api/explore/cards의 id") String> cardIds,
        Boolean consent) {
}
