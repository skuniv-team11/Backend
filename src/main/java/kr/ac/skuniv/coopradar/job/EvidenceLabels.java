package kr.ac.skuniv.coopradar.job;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 근거 필드명(field_evidence.field_key) → 화면 표기. 키 집합은 V1 {@code field_evidence_allowed_key} CHECK와 같다
 * (JobUnitTest가 DDL과 대조). 순서가 직무 상세 {@code evidence}의 순서다 — 직무 필드 먼저, 기관 필드 다음.
 */
public final class EvidenceLabels {

    /** 직무 필드(DDL 순서). */
    static final Map<String, String> JOB = ordered(
            "department", "부서",
            "jobTitle", "직무명",
            "workAddress", "근무지",
            "course", "실습 과정",
            "jobType", "실습 유형",
            "period", "실습 기간",
            "hours", "실습 시간",
            "weekdays", "실습 요일",
            "overtime", "연장 실습",
            "laborContract", "근로계약",
            "stipendBasis", "지원비 기준",
            "stipendAmount", "실습지원비",
            "benefits", "복리후생",
            "educationGoal", "교육 목표",
            "jobOverview", "직무 개요",
            "majorRequirement", "선호 전공",
            "headcount", "모집 인원",
            "gradeRequirement", "학년 요건",
            "gpaRequirement", "학점 요건",
            "competencies", "요구 역량",
            "portfolio", "포트폴리오",
            "certificate", "자격증");

    /** 기관 필드(DDL 순서). */
    static final Map<String, String> INSTITUTION = ordered(
            "name", "기관명",
            "size", "기업 규모",
            "listing", "상장 여부",
            "businessType", "업태",
            "businessItem", "종목",
            "address", "소재지",
            "applicationDeadline", "접수 마감");

    private static final List<String> ORDER;

    static {
        var all = new java.util.ArrayList<String>(JOB.keySet());
        all.addAll(INSTITUTION.keySet());
        ORDER = List.copyOf(all);
    }

    private EvidenceLabels() {
    }

    /** 표기. 모르는 키면 키 그대로(DB CHECK가 막으므로 실제로는 없다). */
    public static String label(String fieldKey) {
        String label = JOB.get(fieldKey);
        if (label == null) {
            label = INSTITUTION.get(fieldKey);
        }
        return label == null ? fieldKey : label;
    }

    /** 정렬 순서. 모르는 키는 맨 뒤. */
    static int order(String fieldKey) {
        int i = ORDER.indexOf(fieldKey);
        return i < 0 ? Integer.MAX_VALUE : i;
    }

    private static Map<String, String> ordered(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return Collections.unmodifiableMap(map);
    }
}
