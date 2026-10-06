package kr.ac.skuniv.coopradar.job;

/**
 * M2 검토 알림(계약 스키마 Alert). 직무 상세와 센터 현황판이 같이 쓴다. 필드 이름이 JSON 이름이다.
 *
 * @param jobId    기관 전체에 걸린 알림이면 null
 * @param kind     DOC_INCONSISTENCY · RULE_CHECK · LIST_MISMATCH
 * @param fieldKey      판정 항목(majorRequirement 등)에 걸린 알림이면 그 필드명. 없으면 null
 * @param documentTitle pageA·quoteA(와 pageB·quoteB)가 있는 문서 이름(예: 소서 운영계획서). 문서가 없으면 null(ADR-0023)
 */
public record Alert(
        int id,
        InstitutionRef institution,
        Integer jobId,
        String kind,
        String fieldKey,
        String description,
        String documentTitle,
        Integer pageA,
        String quoteA,
        Integer pageB,
        String quoteB) {
}
