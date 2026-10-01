package kr.ac.skuniv.coopradar.eligibility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import kr.ac.skuniv.coopradar.eligibility.EligibilityDtos.EligibilityJob;
import kr.ac.skuniv.coopradar.eligibility.EligibilityDtos.Layer;
import kr.ac.skuniv.coopradar.eligibility.EligibilityDtos.MajorMatch;
import kr.ac.skuniv.coopradar.eligibility.EligibilityDtos.ReasonLine;
import kr.ac.skuniv.coopradar.eligibility.EligibilityDtos.Result;
import kr.ac.skuniv.coopradar.eligibility.EligibilityDtos.Verdict;
import kr.ac.skuniv.coopradar.eligibility.JobRequirement.AlertRef;
import kr.ac.skuniv.coopradar.job.InstitutionRef;
import org.junit.jupiter.api.Test;

/** 3층 판정 규칙(docs/api/README.md '판정', ADR-0012). DB 없이 규칙만 본다. */
class EligibilityRulesTest {

    private static final int DEPT = 43;

    @Test
    void 요건을_모두_채우면_지원_가능이고_선호_전공은_참고_줄만_붙는다() {
        EligibilityJob r = judge(job(), me(3, 5, "3.4", false));
        assertThat(r.verdict()).isEqualTo(Verdict.ELIGIBLE);
        assertThat(r.majorMatch()).isEqualTo(MajorMatch.MATCH);
        assertThat(r.reasons()).extracting(ReasonLine::layer, ReasonLine::item, ReasonLine::requirement,
                        ReasonLine::mine, ReasonLine::result)
                .containsExactly(
                        tuple(Layer.SCHOOL_RULE, "이수 학기", "4학기 이상", "5학기", Result.MET),
                        tuple(Layer.INSTITUTION, "학년", "3·4학년", "3학년", Result.MET),
                        tuple(Layer.INSTITUTION, "학점", "3.0 이상", "3.4", Result.MET),
                        tuple(Layer.MAJOR, "선호 전공", "미용예술대학", "메이크업디자인학과",
                                Result.INFO));
        assertThat(r.reasons()).allSatisfy(line -> assertThat(line.alertId()).isNull());
    }

    @Test
    void 이수_학기가_4학기_미만이면_다른_요건과_상관없이_지원_불가() {
        EligibilityJob r = judge(job(), me(3, 3, "4.5", false));
        assertThat(r.verdict()).isEqualTo(Verdict.INELIGIBLE);
        assertThat(line(r, "이수 학기").result()).isEqualTo(Result.NOT_MET);
        assertThat(line(r, "이수 학기").mine()).isEqualTo("3학기");
        assertThat(judge(job(), me(3, 4, "3.4", false)).verdict()).isEqualTo(Verdict.ELIGIBLE);
    }

    @Test
    void 졸업예정자_계절제_줄은_방학_과정에만_붙고_졸업예정이면_지원_불가() {
        JobRequirement vacation = job(b -> b.course = "VACATION");
        EligibilityJob blocked = judge(vacation, me(4, 7, "3.4", true));
        assertThat(blocked.verdict()).isEqualTo(Verdict.INELIGIBLE);
        assertThat(line(blocked, "졸업예정자 계절제").result()).isEqualTo(Result.NOT_MET);

        EligibilityJob ok = judge(vacation, me(4, 7, "3.4", false));
        assertThat(ok.verdict()).isEqualTo(Verdict.ELIGIBLE);
        assertThat(line(ok, "졸업예정자 계절제").result()).isEqualTo(Result.MET);

        // 방학·학기 연계 과정은 센터 확인 전이라 판정하지 않는다(ADR-0012)
        EligibilityJob linked = judge(job(b -> b.course = "VACATION_SEMESTER"), me(4, 7, "3.4", true));
        assertThat(linked.reasons()).noneMatch(l -> l.item().equals("졸업예정자 계절제"));
        assertThat(linked.verdict()).isEqualTo(Verdict.ELIGIBLE);
    }

    @Test
    void 기관_학년_조건은_미충족이어도_확인_필요() {
        EligibilityJob y34 = judge(job(), me(2, 4, "3.4", false));
        assertThat(y34.verdict()).isEqualTo(Verdict.NEEDS_CHECK);
        assertThat(line(y34, "학년").result()).isEqualTo(Result.NOT_MET);

        JobRequirement y4 = job(b -> b.gradeRule = "Y4");
        assertThat(line(judge(y4, me(3, 5, "3.4", false)), "학년").result()).isEqualTo(Result.NOT_MET);
        assertThat(line(judge(y4, me(4, 7, "3.4", false)), "학년").requirement()).isEqualTo("4학년");
        assertThat(judge(y4, me(4, 7, "3.4", false)).verdict()).isEqualTo(Verdict.ELIGIBLE);

        JobRequirement graduating = job(b -> b.gradeRule = "GRADUATING");
        ReasonLine notGraduating = line(judge(graduating, me(4, 7, "3.4", false)), "학년");
        assertThat(notGraduating.requirement()).isEqualTo("졸업예정자");
        assertThat(notGraduating.mine()).isEqualTo("졸업예정 아님");
        assertThat(notGraduating.result()).isEqualTo(Result.NOT_MET);
        assertThat(judge(graduating, me(4, 7, "3.4", true)).verdict()).isEqualTo(Verdict.ELIGIBLE);
    }

    @Test
    void 학점은_하한과_같으면_충족_하한이_없으면_줄이_없다() {
        assertThat(line(judge(job(), me(3, 5, "3.0", false)), "학점").result()).isEqualTo(Result.MET);
        EligibilityJob below = judge(job(b -> b.gpaMin = new BigDecimal("3.5")), me(3, 5, "3.4", false));
        assertThat(line(below, "학점").result()).isEqualTo(Result.NOT_MET);
        assertThat(line(below, "학점").requirement()).isEqualTo("3.5 이상");
        assertThat(below.verdict()).isEqualTo(Verdict.NEEDS_CHECK);

        EligibilityJob none = judge(job(b -> b.gpaMin = null), me(3, 5, "0.0", false));
        assertThat(none.reasons()).noneMatch(l -> l.item().equals("학점"));
        assertThat(none.verdict()).isEqualTo(Verdict.ELIGIBLE);
    }

    @Test
    void 포트폴리오_자격증은_필수면_확인_필요_우대면_참고() {
        EligibilityJob required = judge(job(b -> {
            b.portfolio = "REQUIRED";
            b.certificate = "REQUIRED";
            b.certificateText = "정보처리기사";
        }), me(3, 5, "3.4", false));
        assertThat(required.verdict()).isEqualTo(Verdict.NEEDS_CHECK);
        assertThat(line(required, "포트폴리오").result()).isEqualTo(Result.CHECK);
        assertThat(line(required, "포트폴리오").mine()).isEqualTo("직접 확인");
        assertThat(line(required, "자격증").requirement()).isEqualTo("필수 (정보처리기사)");

        EligibilityJob preferred = judge(job(b -> b.portfolio = "PREFERRED"), me(3, 5, "3.4", false));
        assertThat(line(preferred, "포트폴리오").result()).isEqualTo(Result.INFO);
        assertThat(preferred.verdict()).isEqualTo(Verdict.ELIGIBLE);
    }

    @Test
    void 판정_항목에_걸린_검토_알림만_확인_필요와_alertId를_붙인다() {
        EligibilityJob r = judge(job(b -> b.alerts = List.of(
                new AlertRef(4, "LIST_MISMATCH", "majorRequirement"),
                new AlertRef(5, "LIST_MISMATCH", "period"),
                new AlertRef(6, "DOC_INCONSISTENCY", "gpaRequirement"))), me(3, 5, "3.4", false));
        assertThat(r.verdict()).isEqualTo(Verdict.NEEDS_CHECK);
        List<ReasonLine> alertLines = r.reasons().stream().filter(l -> l.alertId() != null).toList();
        assertThat(alertLines).extracting(ReasonLine::alertId).containsExactly(4, 6);
        assertThat(alertLines.get(0).item()).isEqualTo("선호 전공 표기");
        assertThat(alertLines.get(0).requirement()).isEqualTo("참여기관 리스트와 운영계획서가 다르게 적혀 있음");
        assertThat(alertLines.get(1).item()).isEqualTo("학점 요건 표기");
        assertThat(alertLines.get(1).requirement()).isEqualTo("문서 안에서 서로 다르게 적혀 있음");
        assertThat(alertLines).allSatisfy(l -> {
            assertThat(l.layer()).isEqualTo(Layer.INSTITUTION);
            assertThat(l.result()).isEqualTo(Result.CHECK);
            assertThat(l.mine()).isEqualTo("—");
        });
    }

    @Test
    void 선호_전공은_무관이면_OPEN_매핑_밖이면_NOT_LISTED이고_판정에_넣지_않는다() {
        EligibilityJob open = judge(job(b -> {
            b.majorOpen = true;
            b.majorDepartmentIds = Set.of();
        }), me(3, 5, "3.4", false));
        assertThat(open.majorMatch()).isEqualTo(MajorMatch.OPEN);
        assertThat(line(open, "선호 전공").requirement()).isEqualTo("전공 무관");

        EligibilityJob outside = judge(job(b -> b.majorDepartmentIds = Set.of(1, 2)), me(3, 5, "3.4", false));
        assertThat(outside.majorMatch()).isEqualTo(MajorMatch.NOT_LISTED);
        assertThat(outside.verdict()).isEqualTo(Verdict.ELIGIBLE);
    }

    // ───────── 도우미 ─────────

    private static EligibilityJob judge(JobRequirement job, ProfileInput me) {
        return EligibilityRules.judge(job, me, "메이크업디자인학과");
    }

    private static ReasonLine line(EligibilityJob r, String item) {
        return r.reasons().stream().filter(l -> l.item().equals(item)).findFirst()
                .orElseThrow(() -> new AssertionError("이유 줄 없음: " + item));
    }

    private static ProfileInput me(int grade, int semesters, String gpa, boolean graduating) {
        return new ProfileInput(DEPT, grade, semesters, new BigDecimal(gpa), graduating, null, null);
    }

    private static JobRequirement job() {
        return job(b -> {
        });
    }

    private static JobRequirement job(Consumer<Builder> change) {
        Builder b = new Builder();
        change.accept(b);
        return new JobRequirement(101, 1, "(가상)마케팅", "(가상)마케팅팀", new InstitutionRef(1, "(가상)기관"), b.course,
                b.gradeRule, b.gpaMin, b.portfolio, b.certificate, b.certificateText, "미용예술대학", b.majorOpen,
                b.majorDepartmentIds, b.alerts);
    }

    private static final class Builder {
        String course = "SEMESTER";
        String gradeRule = "Y3_4";
        BigDecimal gpaMin = new BigDecimal("3.0");
        String portfolio = "NONE";
        String certificate = "NONE";
        String certificateText = null;
        boolean majorOpen = false;
        Set<Integer> majorDepartmentIds = Set.of(DEPT);
        List<AlertRef> alerts = List.of();
    }
}
