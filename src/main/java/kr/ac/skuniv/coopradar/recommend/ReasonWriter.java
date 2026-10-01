package kr.ac.skuniv.coopradar.recommend;

import java.util.Optional;

/** 이유 문장 초안을 만드는 LLM 호출. 실패·키 없음·시간 초과면 빈 값(호출한 쪽이 기본 문장으로 바꾼다). */
public interface ReasonWriter {

    Optional<ReasonDraft> write(String systemPrompt, String userMessage);
}
