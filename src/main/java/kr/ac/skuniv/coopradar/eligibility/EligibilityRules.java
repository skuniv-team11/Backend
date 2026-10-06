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

/**
 * 자격 판정 3층 규칙(docs/api/README.md '판정', ADR-0012). AI를 쓰지 않는 규칙 엔진이고 DB를 모른다.
 * <ol>
 *   <li>학교 규정(SCHOOL_RULE): 이수 학기 4학기 이상 · 졸업예정자는 방학 과정(VACATION) 불가. MET·NOT_MET만</li>
 *   <li>기관 조건(INSTITUTION): 학년(3·4학년 / 4학년 / 졸업예정자) · 학점 하한 · 포트폴리오 · 자격증.
 *       자격증은 프로필의 certificates와 직무의 자격증 코드를 맞춰 본다 — 필수인데 없으면 NOT_MET(ADR-0021).
 *       검토 알림의 fieldKey가 이 넷 중 하나면 그 항목 행이 CHECK가 되고 alertId가 붙는다(ADR-0016).
 *       그 밖의 알림(선호 전공·기간·지원비 등)은 판정을 바꾸지 않는다 — 목록의 alertCount로만 보인다</li>
 *   <li>선호 전공(MAJOR): 참고 표시(INFO)만. 판정에 넣지 않는다</li>
 * </ol>
 * 판정: SCHOOL_RULE에 NOT_MET 또는 자격증 줄이 NOT_MET(필수 자격증 없음) → INELIGIBLE,
 * 아니면 INSTITUTION에 NOT_MET·CHECK → NEEDS_CHECK, 아니면 ELIGIBLE.
 */
public final class EligibilityRules {

    static final int MIN_COMPLETED_SEMESTERS = 4;

    /** 자격증 줄의 항목 이름. 기관 조건 중 이 줄의 NOT_MET만 지원 불가로 간다(ADR-0021). */
    public static final String CERTIFICATE_ITEM = "자격증";

    /** 판정 항목 필드 → 이유 줄의 항목 이름(ADR-0016). 이 필드에 걸린 알림만 판정을 바꾼다. */
    static final Map<String, String> JUDGED_FIELDS = Map.of(
            "gradeRequirement", "학년",
            "gpaRequirement", "학점",
            "portfolio", "포트폴리오",
            "certificate", CERTIFICATE_ITEM);

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
                job.closing(), job.alertCount(), List.copyOf(reasons));
    }

    static Verdict verdict(List<ReasonLine> reasons) {
        if (reasons.stream().anyMatch(EligibilityRules::blocks)) {
            return Verdict.INELIGIBLE;
        }
        boolean institutionOpen = reasons.stream()
                .anyMatch(r -> r.layer() == Layer.INSTITUTION && (r.result() == Result.NOT_MET || r.result() == Result.CHECK));
        return institutionOpen ? Verdict.NEEDS_CHECK : Verdict.ELIGIBLE;
    }

    /**
     * 이 줄이 지원 불가를 만드는지: 학교 규정 미충족, 또는 필수 자격증 없음(ADR-0021). 학년·학점 미충족은 기관이 학생을 보고
     * 바꿀 여지가 있어 확인 필요로 둔다. 추천이 0개일 때 막은 항목(blockedBy)도 이 줄로 센다.
     */
    public static boolean blocks(ReasonLine r) {
        return r.result() == Result.NOT_MET && (r.layer() == Layer.SCHOOL_RULE
                || r.layer() == Layer.INSTITUTION && CERTIFICATE_ITEM.equals(r.item()));
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
        certificate(out, job, me.certificates());
        for (AlertRef alert : job.alerts()) {
            String item = JUDGED_FIELDS.get(alert.fieldKey());
            if (item == null) {
                continue;
            }
            int at = indexOf(out, item);
            if (at >= 0) {
                // 그 항목 행을 '확인 필요'로 바꾼다(요건·내 값은 그대로)
                ReasonLine line = out.get(at);
                out.set(at, new ReasonLine(line.layer(), line.item(), line.requirement(), line.mine(), Result.CHECK,
                        alert.id(), line.citation()));
            } else {
                // 행이 없던 항목(예: 학점 하한이 없는데 학점 요건이 엇갈림)은 새로 만든다
                out.add(new ReasonLine(Layer.INSTITUTION, item,
                        ALERT_REQUIREMENT.getOrDefault(alert.kind(), "검토가 필요함"), "—", Result.CHECK, alert.id(), null));
            }
        }
    }

    private static int indexOf(List<ReasonLine> lines, String item) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).layer() == Layer.INSTITUTION && lines.get(i).item().equals(item)
                    && lines.get(i).alertId() == null) {
                return i;
            }
        }
        return -1;
    }

    /** 필수면 학생이 직접 확인할 항목(CHECK), 우대면 참고(INFO), 없으면 줄을 만들지 않는다. */
    private static void document(List<ReasonLine> out, String item, String requirement, String detail) {
        switch (requirement) {
            case "REQUIRED" -> out.add(ReasonLine.of(Layer.INSTITUTION, item, "필수" + detail(detail), "직접 확인",
                    Result.CHECK));
            case "PREFERRED" -> out.add(ReasonLine.of(Layer.INSTITUTION, item, "우대" + detail(detail), "직접 확인",
                    Result.INFO));
            default -> {
            }
        }
    }

    /**
     * 자격증 줄(ADR-0021). 프로필의 certificates에 직무의 자격증 코드가 있는지 본다.
     * 답하지 않았으면(null) 지금까지처럼 필수는 직접 확인(CHECK), 우대는 참고(INFO).
     * 필수인데 없으면 NOT_MET → 판정이 지원 불가가 된다. 우대는 있든 없든 참고(INFO)다. 운영계획서 근거를 붙인다.
     */
    private static void certificate(List<ReasonLine> out, JobRequirement job, List<String> mine) {
        boolean required = "REQUIRED".equals(job.certificate());
        if (!required && !"PREFERRED".equals(job.certificate())) {
            return;
        }
        String have;
        Result result;
        if (mine == null || job.certificateCode() == null) {
            have = "직접 확인";
            result = required ? Result.CHECK : Result.INFO;
        } else if (mine.contains(job.certificateCode())) {
            have = "있음";
            result = required ? Result.MET : Result.INFO;
        } else {
            have = "없음";
            result = required ? Result.NOT_MET : Result.INFO;
        }
        String requirement = (required ? "필수" : "우대") + detail(job.certificateText());
        out.add(new ReasonLine(Layer.INSTITUTION, CERTIFICATE_ITEM, requirement, have, result, null,
                job.certificateCitation()));
    }

    private static String detail(String text) {
        return text == null || text.isBlank() ? "" : " (" + text.strip() + ")";
    }

    private static String oneDecimal(BigDecimal value) {
        return value.setScale(1, RoundingMode.HALF_UP).toPlainString();
    }

    private static String orDash(String text) {
        return text == null || text.isBlank() ? "—" : text;
    }
}
