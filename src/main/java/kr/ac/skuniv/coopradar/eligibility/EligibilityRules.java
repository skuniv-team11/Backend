package kr.ac.skuniv.coopradar.eligibility;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import kr.ac.skuniv.coopradar.eligibility.EligibilityDtos.EligibilityJob;
import kr.ac.skuniv.coopradar.eligibility.EligibilityDtos.Layer;
import kr.ac.skuniv.coopradar.eligibility.EligibilityDtos.MajorMatch;
import kr.ac.skuniv.coopradar.eligibility.EligibilityDtos.ReasonLine;
import kr.ac.skuniv.coopradar.eligibility.EligibilityDtos.Result;
import kr.ac.skuniv.coopradar.eligibility.EligibilityDtos.Verdict;
import kr.ac.skuniv.coopradar.eligibility.JobRequirement.AlertRef;
import kr.ac.skuniv.coopradar.job.EvidenceLabels;

/**
 * 자격 판정 3층 규칙(docs/api/README.md '판정', ADR-0012). AI를 쓰지 않는 규칙 엔진이고 DB를 모른다.
 * <ol>
 *   <li>학교 규정(SCHOOL_RULE): 이수 학기 4학기 이상 · 졸업예정자는 방학 과정(VACATION) 불가. MET·NOT_MET만</li>
 *   <li>기관 조건(INSTITUTION): 학년(3·4학년 / 4학년 / 졸업예정자) · 학점 하한 · 포트폴리오 · 자격증 · 판정 항목에 걸린 검토 알림</li>
 *   <li>선호 전공(MAJOR): 참고 표시(INFO)만. 판정에 넣지 않는다</li>
 * </ol>
 * 판정: SCHOOL_RULE에 NOT_MET → INELIGIBLE, 아니면 INSTITUTION에 NOT_MET·CHECK → NEEDS_CHECK, 아니면 ELIGIBLE.
 */
public final class EligibilityRules {

    static final int MIN_COMPLETED_SEMESTERS = 4;

    /**
     * 판정 항목 필드 → 검토 알림 줄의 항목 이름. 이 필드에 걸린 알림만 '확인 필요'로 만든다
     * (기간·지원비 같은 알림은 직무 상세에만 보인다).
     */
    static final List<String> JUDGED_FIELDS = List.of(
            "course", "gradeRequirement", "gpaRequirement", "majorRequirement", "portfolio", "certificate");

    private static final Map<String, String> ALERT_REQUIREMENT = Map.of(
            "DOC_INCONSISTENCY", "문서 안에서 서로 다르게 적혀 있음",
            "LIST_MISMATCH", "참여기관 리스트와 운영계획서가 다르게 적혀 있음",
            "RULE_CHECK", "규정 점검이 필요함");

    private EligibilityRules() {
    }

    public static EligibilityJob judge(JobRequirement job, ProfileInput me, String departmentName) {
        List<ReasonLine> reasons = new ArrayList<>();
        schoolRules(job, me, reasons);
        institutionConditions(job, me, reasons);
        MajorMatch match = majorMatch(job, me.departmentId());
        reasons.add(ReasonLine.of(Layer.MAJOR, "선호 전공",
                job.majorOpen() ? "전공 무관" : orDash(job.majorText()), departmentName, Result.INFO));
        return new EligibilityJob(job.jobId(), job.title(), job.team(), job.institution(), verdict(reasons), match,
                List.copyOf(reasons));
    }

    static Verdict verdict(List<ReasonLine> reasons) {
        boolean schoolBlocked = reasons.stream()
                .anyMatch(r -> r.layer() == Layer.SCHOOL_RULE && r.result() == Result.NOT_MET);
        if (schoolBlocked) {
            return Verdict.INELIGIBLE;
        }
        boolean institutionOpen = reasons.stream()
                .anyMatch(r -> r.layer() == Layer.INSTITUTION && (r.result() == Result.NOT_MET || r.result() == Result.CHECK));
        return institutionOpen ? Verdict.NEEDS_CHECK : Verdict.ELIGIBLE;
    }

    static MajorMatch majorMatch(JobRequirement job, int departmentId) {
        if (job.majorOpen()) {
            return MajorMatch.OPEN;
        }
        return job.majorDepartmentIds().contains(departmentId) ? MajorMatch.MATCH : MajorMatch.NOT_LISTED;
    }

    private static void schoolRules(JobRequirement job, ProfileInput me, List<ReasonLine> out) {
        int semesters = me.completedSemesters();
        out.add(ReasonLine.of(Layer.SCHOOL_RULE, "이수 학기", MIN_COMPLETED_SEMESTERS + "학기 이상", semesters + "학기",
                semesters >= MIN_COMPLETED_SEMESTERS ? Result.MET : Result.NOT_MET));
        // VACATION_SEMESTER(방학·학기 연계)가 계절제에 드는지는 센터 확인 전이라 붙이지 않는다(ADR-0012)
        if ("VACATION".equals(job.course())) {
            boolean graduating = me.graduationExpected();
            out.add(ReasonLine.of(Layer.SCHOOL_RULE, "졸업예정자 계절제", "졸업예정자는 방학 과정 불가",
                    graduating ? "졸업예정" : "졸업예정 아님", graduating ? Result.NOT_MET : Result.MET));
        }
    }

    private static void institutionConditions(JobRequirement job, ProfileInput me, List<ReasonLine> out) {
        int grade = me.grade();
        switch (job.gradeRule()) {
            case "Y3_4" -> out.add(ReasonLine.of(Layer.INSTITUTION, "학년", "3·4학년", grade + "학년",
                    grade >= 3 ? Result.MET : Result.NOT_MET));
            case "Y4" -> out.add(ReasonLine.of(Layer.INSTITUTION, "학년", "4학년", grade + "학년",
                    grade == 4 ? Result.MET : Result.NOT_MET));
            case "GRADUATING" -> out.add(ReasonLine.of(Layer.INSTITUTION, "학년", "졸업예정자",
                    me.graduationExpected() ? "졸업예정" : "졸업예정 아님",
                    me.graduationExpected() ? Result.MET : Result.NOT_MET));
            default -> throw new IllegalStateException("모르는 grade_rule: " + job.gradeRule());
        }
        if (job.gpaMin() != null) {
            out.add(ReasonLine.of(Layer.INSTITUTION, "학점", oneDecimal(job.gpaMin()) + " 이상", oneDecimal(me.gpa()),
                    me.gpa().compareTo(job.gpaMin()) >= 0 ? Result.MET : Result.NOT_MET));
        }
        document(out, "포트폴리오", job.portfolio(), null);
        document(out, "자격증", job.certificate(), job.certificateText());
        for (AlertRef alert : job.alerts()) {
            if (!JUDGED_FIELDS.contains(alert.fieldKey())) {
                continue;
            }
            out.add(new ReasonLine(Layer.INSTITUTION, EvidenceLabels.label(alert.fieldKey()) + " 표기",
                    ALERT_REQUIREMENT.getOrDefault(alert.kind(), "검토가 필요함"), "—", Result.CHECK, alert.id()));
        }
    }

    /** 필수면 학생이 직접 확인할 항목(CHECK), 우대면 참고(INFO), 없으면 줄을 만들지 않는다. */
    private static void document(List<ReasonLine> out, String item, String requirement, String detail) {
        String suffix = detail == null || detail.isBlank() ? "" : " (" + detail.strip() + ")";
        switch (requirement) {
            case "REQUIRED" -> out.add(ReasonLine.of(Layer.INSTITUTION, item, "필수" + suffix, "직접 확인", Result.CHECK));
            case "PREFERRED" -> out.add(ReasonLine.of(Layer.INSTITUTION, item, "우대" + suffix, "직접 확인", Result.INFO));
            default -> {
            }
        }
    }

    private static String oneDecimal(BigDecimal value) {
        return value.setScale(1, RoundingMode.HALF_UP).toPlainString();
    }

    private static String orDash(String text) {
        return text == null || text.isBlank() ? "—" : text;
    }
}
