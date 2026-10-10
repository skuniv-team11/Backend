package kr.ac.skuniv.coopradar.explore;

import java.util.Optional;
import kr.ac.skuniv.coopradar.explore.ExploreDrafts.RankDraft;
import kr.ac.skuniv.coopradar.explore.ExploreDrafts.WhyDraft;

/** 직무 탐색 AI 호출. 실패·시간 초과면 빈 값(호출한 쪽이 규칙 추천이나 why null로 바꾼다). 테스트는 가짜로 바꿔 끼운다. */
public interface ExploreWriter {

    /** 키가 있어 AI를 부를 수 있는지. */
    boolean available();

    /** 모델 이름(결과에 남긴다). */
    String model();

    /** 탐색 순서. system = 후보 직무 원문 + 규칙(같은 후보면 같은 글 — 프롬프트 캐시). */
    Optional<RankDraft> rank(String system, String user);

    /** 직무 하나의 '왜 맞나요'. */
    Optional<WhyDraft> why(String system, String user);
}
