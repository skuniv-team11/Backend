package kr.ac.skuniv.coopradar.reference;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 코드값 → 화면 표기(docs/api #2 {@code GET /api/codes}). 계약 예시 {@code docs/api/codes.json}과 똑같아야 한다
 * (ReferenceApiTest가 값까지 대조). 코드 집합은 V1 CHECK와 같다(check_api_docs.py가 codes.json ↔ DDL을 대조).
 * 코드를 더하거나 표기를 바꿀 때는 codes.json을 먼저 고치고 여기를 맞춘다.
 */
public final class CodeLabels {

    static final Map<String, Map<String, String>> ALL = build(
            group("verdict", "ELIGIBLE", "지원 가능", "NEEDS_CHECK", "확인 필요", "INELIGIBLE", "지원 불가"),
            group("reasonLayer", "SCHOOL_RULE", "학교 규정", "INSTITUTION", "기관 조건", "MAJOR", "선호 전공"),
            group("reasonResult", "MET", "충족", "NOT_MET", "미충족", "CHECK", "확인 필요", "INFO", "참고"),
            group("majorMatch", "MATCH", "선호 전공", "NEAR", "가까운 전공", "NOT_LISTED", "선호 전공 밖", "OPEN", "전공 무관"),
            group("closeReason", "APPLICATION_DEADLINE", "기관 접수마감", "CENTER_CLOSED", "센터 모집마감"),
            group("risk", "NARROW_POOL", "대상 학과가 좁음", "PORTFOLIO_REQUIRED", "포트폴리오 필수",
                    "CERTIFICATE_REQUIRED", "자격증 필수", "WEEKEND", "주말 실습", "DOC_ALERT", "문서 검토 필요"),
            group("alertKind", "DOC_INCONSISTENCY", "문서 내부 불일치", "RULE_CHECK", "규정 점검",
                    "LIST_MISMATCH", "리스트·계획서 불일치"),
            group("size", "LARGE", "대기업", "MIDSIZE", "중견기업", "SME", "중소기업", "PUBLIC", "공공기관",
                    "ASSOCIATION_ETC", "협회·기타", "UNSPECIFIED", "미기재"),
            group("listing", "KOSPI", "코스피", "KOSDAQ", "코스닥", "UNLISTED", "비상장", "UNSPECIFIED", "미기재"),
            group("ntsStatus", "ACTIVE", "계속사업자", "SUSPENDED", "휴업자", "CLOSED", "폐업자"),
            group("course", "VACATION", "방학과정", "SEMESTER", "학기과정", "VACATION_SEMESTER", "방학·학기 연계과정",
                    "UNSPECIFIED", "미기재"),
            group("jobType", "EXPERIENCE", "직무체험형", "HIRING", "채용연계형", "UNSPECIFIED", "미기재"),
            group("overtime", "NONE", "없음", "OCCASIONAL", "상황별 실시", "REGULAR", "주기적·상시적 실시",
                    "UNSPECIFIED", "미기재"),
            group("stipendBasis", "MONTHLY", "월 기준", "HOURLY", "시간 기준", "UNSPECIFIED", "미기재"),
            group("benefit", "MEAL", "식사", "TRANSPORT", "교통", "DORM", "기숙사", "IN_KIND", "현물"),
            group("weekday", "MON", "월", "TUE", "화", "WED", "수", "THU", "목", "FRI", "금", "SAT", "토", "SUN", "일"),
            group("gradeRule", "Y3_4", "3·4학년", "Y4", "4학년", "GRADUATING", "졸업예정자"),
            group("requirement", "REQUIRED", "필수", "PREFERRED", "우대", "NONE", "없음"),
            group("sourceType", "OPERATION_PLAN", "운영계획서", "TESTIMONIAL", "선배 수기",
                    "INSTITUTION_LIST", "참여기관 리스트", "SCHOOL_NOTICE", "학생 모집안내"),
            group("role", "STUDENT", "학생", "CENTER", "센터 담당자"),
            group("commuteOrigin", "HOME_AREA", "사는 곳", "SCHOOL", "서경대"),
            group("commuteUnavailable", "NO_WORKPLACE", "근무지 위치 없음", "NO_ROUTE", "대중교통 경로 없음",
                    "LIMITED", "조회 한도 초과", "PROVIDER_ERROR", "카카오맵 응답 없음"),
            group("commuteProvider", "KAKAO_MAP", "카카오맵"),
            group("exploreSource", "AI", "AI 탐색", "RULE", "규칙 추천(AI 대신)"),
            group("exploreFallback", "NO_KEY", "AI 설정 없음", "LIMITED", "탐색 한도 초과", "AI_ERROR", "AI 응답 없음",
                    "VERIFY_FAILED", "AI 근거 확인 실패", "NO_CANDIDATES", "지원할 수 있는 자리 없음"),
            group("exploreFit", "STRONG", "잘 맞음", "GOOD", "맞음", "WEAK", "덜 맞음"),
            group("exploreJudgedWith", "SAVED_PROFILE", "저장한 프로필로 다시 판정", "RUN_PROFILE", "탐색 때 보낸 프로필"),
            group("careerSource", "AI", "AI가 능력단위를 고름", "NONE", "AI 정리 없음(능력단위 목록만)"),
            group("ncsRelation", "SAME_SMALL", "같은 분야(소분류)", "SAME_MIDDLE", "가까운 분야(중분류)", "OTHER", "다른 분야"),
            group("occupationOrigin", "KEIS", "한국고용정보원 연계표", "CURATED", "연계표에 없어 팀이 고름"),
            group("applicationStatus", "NONE", "작성 전", "DRAFT", "작성 중", "SUBMITTED", "새로 들어옴", "RECEIVED", "접수 완료",
                    "FIX_REQUESTED", "보완 요청", "MATCHED", "매칭"),
            group("applicationItem", "PROFILE", "프로필", "PICKS", "1~3지망", "APPLICANT", "신청서 기본정보", "PLEDGE", "서약",
                    "ESSAYS", "자기소개서 4문항", "CONSENTS", "개인정보 동의 2개", "SIGNATURE", "본인 서명", "APPROVAL", "학과(부)장 승인"),
            group("approvalStatus", "NONE", "요청 전", "REQUESTED", "승인 대기", "APPROVED", "승인됨", "STALE", "다시 요청(내용이 바뀜)"),
            group("approvalKind", "APPLICATION", "지원서", "CREDIT", "학점 인정"),
            group("gender", "M", "남", "F", "여"),
            group("resumeKind", "CERTIFICATE", "자격증", "LANGUAGE", "어학", "EDUCATION", "교육"),
            group("stage", "PICK", "지망 정하기", "APPLY", "지원서", "MATCH", "매칭", "SELECT", "면접·선발", "CONTRACT", "협약·보험",
                    "ORIENTATION", "사전교육", "PRACTICE", "실습", "MIDCHECK", "중간점검", "CLOSE", "종료·제출",
                    "DEBRIEF", "후기 간담회", "CREDIT", "학점 인정"),
            group("stagePhase", "APPLY", "지원", "PREPARE", "준비", "PRACTICE", "실습", "CLOSE", "마무리"),
            group("stageState", "DONE", "완료", "NOW", "지금", "NEXT", "앞으로"),
            group("nextKind", "START", "시작까지", "END", "마감까지"),
            group("interviewMode", "IN_PERSON", "대면", "ONLINE", "화상", "PHONE", "전화"),
            group("selectionResult", "WAIT", "대기", "PASS", "합격", "FAIL", "불합격"),
            group("studentDocument", "REPORT", "수행결과보고서(제9호)", "CREDIT", "학점인정신청서(제7호)", "SURVEY", "설문조사서(제8호)"),
            group("closeItem", "REPORT", "수행결과보고서", "CREDIT", "학점인정신청서", "SURVEY", "설문조사서", "EVALUATION", "기관 평가표",
                    "ATTENDANCE", "출근부", "APPROVAL", "학과(부)장 승인"),
            group("guestStage", "APPLYING", "지원 중(7/23)", "PRACTICING", "실습 중(10/14)", "DONE", "실습 마친 뒤(12/17)"),
            group("demoStep", "RECEIVED", "접수까지", "MATCHED", "매칭 확정까지", "SELECTED", "선발 결과 알림까지",
                    "CLOSING", "마무리 서류까지"));

    private CodeLabels() {
    }

    /** 코드값의 화면 표기. 모르는 묶음·코드면 코드값 그대로. */
    public static String label(String group, String code) {
        Map<String, String> labels = ALL.get(group);
        String label = labels == null ? null : labels.get(code);
        return label == null ? code : label;
    }

    private record Group(String name, Map<String, String> labels) {
    }

    /** 코드값과 표기를 번갈아 받는다(순서 유지). */
    private static Group group(String name, String... codeLabelPairs) {
        if (codeLabelPairs.length % 2 != 0) {
            throw new IllegalArgumentException(name + ": 코드값과 표기가 짝이 맞지 않음");
        }
        Map<String, String> labels = new LinkedHashMap<>();
        for (int i = 0; i < codeLabelPairs.length; i += 2) {
            if (labels.put(codeLabelPairs[i], codeLabelPairs[i + 1]) != null) {
                throw new IllegalArgumentException(name + ": 코드값 중복 " + codeLabelPairs[i]);
            }
        }
        return new Group(name, Collections.unmodifiableMap(labels));
    }

    private static Map<String, Map<String, String>> build(Group... groups) {
        Map<String, Map<String, String>> all = new LinkedHashMap<>();
        for (Group g : groups) {
            if (all.put(g.name(), g.labels()) != null) {
                throw new IllegalArgumentException("코드 묶음 중복 " + g.name());
            }
        }
        return Collections.unmodifiableMap(all);
    }
}
