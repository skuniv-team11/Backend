package kr.ac.skuniv.coopradar.recommend;

/**
 * 지난 매칭 집계(ADR-0028) — 현장실습지원센터 기관-학생 매칭 결과 5회차(2025-2 1·2차, 2026-1 1·2차, 2026-2)를 학교 전체
 * 단계별 합계로만 센 값. 이름·연락처는 읽지 않았고, 학과×직무로 나누지 않는다(ADR-0004 — 칸당 1~2명이라 개인을 알아볼 수
 * 있다). 센터 동의(10/7)를 받고 이유 문장의 근거로만 쓴다. 회차가 늘면 오프라인에서 다시 세어 고친다.
 * <ul>
 *   <li>선호 전공 밖 = 매칭 직무의 리스트 선호 전공(표기 → 학과)에 학과가 없음. 전공 무관 직무·경계('인문계열'에 인문사회
 *       학과)는 밖으로 세지 않았다</li>
 *   <li>가까운 전공 = 학과를 콕 집은 선호 전공 표기의 학과 중 하나와 같은 묶음(curated/department_clusters.csv) — 계열·단과대
 *       표기('상경계열' 등)로만 적힌 직무는 따지지 않는다. 먼 전공 = 그 밖</li>
 * </ul>
 */
final class MatchingHistory {

    /** 집계한 회차 범위(문장에 그대로 쓴다). */
    static final String PERIOD = "2025-2~2026-2";
    static final int TOTAL = 73;
    static final int OUTSIDE = 18;
    static final int NEAR = 11;
    static final int FAR = 7;

    private MatchingHistory() {
    }

    /** 가까운 전공 문장 뒤에 붙는 근거. */
    static String nearEvidence() {
        return "지난 매칭(" + PERIOD + ")에서 선호 전공 밖 학생 " + OUTSIDE + "명 중 " + NEAR + "명이 이런 가까운 전공이었어요.";
    }

    /** 먼 전공 문장 뒤에 붙는 근거. */
    static String farEvidence() {
        return "지난 매칭(" + PERIOD + ")에서 이렇게 먼 전공으로 매칭된 학생은 " + TOTAL + "명 중 " + FAR + "명이었어요.";
    }
}
