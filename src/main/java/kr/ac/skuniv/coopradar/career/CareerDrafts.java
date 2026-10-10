package kr.ac.skuniv.coopradar.career;

import java.util.List;

/** AI가 구조화 출력으로 돌려주는 능력단위 초안(ADR-0003·0032). {@link CareerService}가 원문과 대조한다. */
public final class CareerDrafts {

    private CareerDrafts() {
    }

    public record UnitsDraft(List<CoveredDraft> covered) {
    }

    /**
     * @param unitCode     능력단위 코드(요청에 준 목록 안)
     * @param studentQuote 실습 내용 한 문장 안의 구절
     * @param reason       해요체 한 문장
     */
    public record CoveredDraft(String unitCode, String studentQuote, String reason) {
    }
}
