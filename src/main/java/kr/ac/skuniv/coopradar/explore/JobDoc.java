package kr.ac.skuniv.coopradar.explore;

import java.util.List;
import kr.ac.skuniv.coopradar.job.InstitutionRef;

/**
 * 탐색에 쓰는 직무 원문 하나(시드 읽기). 쪽은 운영계획서 근거 쪽(field_evidence), 없으면 null.
 *
 * @param weeklyPlan 주차 계획 내용(seq 순)
 * @param planTitle  운영계획서 문서명(예: 소서 운영계획서)
 */
record JobDoc(int jobId, int listSeq, InstitutionRef institutionRef, String team, String title, String overview,
              String educationGoal, String competencies, List<String> weeklyPlan, Integer overviewPage,
              Integer competenciesPage, Integer goalPage, Integer majorPage, String planTitle) {

    String institution() {
        return institutionRef.name();
    }

    /** 칸의 근거 쪽. 주차 계획은 쪽이 따로 없어 직무 개요와 그다음 칸(전공 요건, 없으면 요구 역량)이 같은 쪽일 때만(ADR-0020). */
    Integer page(ExploreText.Field field) {
        return switch (field) {
            case OVERVIEW -> overviewPage;
            case COMPETENCY -> competenciesPage;
            case GOAL -> goalPage;
            case WEEKLY -> {
                Integer after = majorPage != null ? majorPage : competenciesPage;
                yield overviewPage != null && overviewPage.equals(after) ? overviewPage : null;
            }
            case TITLE, TEAM -> null;
        };
    }
}
