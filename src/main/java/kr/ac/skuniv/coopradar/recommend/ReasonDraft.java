package kr.ac.skuniv.coopradar.recommend;

import java.util.List;

/**
 * LLM이 구조화 출력으로 돌려주는 이유 문장 초안(#16). 그대로 내보내지 않고 {@link ReasonService}가 검증한다.
 *
 * @param text            학생에게 보여 줄 이유 1~2문장
 * @param citationNumbers 문장이 기대는 근거 번호(요청에 준 [1]·[2] 번호)
 */
public record ReasonDraft(String text, List<Long> citationNumbers) {
}
