package kr.ac.skuniv.coopradar.eligibility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import kr.ac.skuniv.coopradar.eligibility.EligibilityDtos.EligibilityJob;
import kr.ac.skuniv.coopradar.eligibility.EligibilityDtos.Layer;
import kr.ac.skuniv.coopradar.eligibility.EligibilityDtos.MajorMatch;
import kr.ac.skuniv.coopradar.eligibility.EligibilityDtos.ReasonCitation;
import kr.ac.skuniv.coopradar.eligibility.EligibilityDtos.ReasonLine;
import kr.ac.skuniv.coopradar.eligibility.EligibilityDtos.Result;
import kr.ac.skuniv.coopradar.eligibility.EligibilityDtos.Verdict;
import kr.ac.skuniv.coopradar.eligibility.JobRequirement.AlertRef;
import kr.ac.skuniv.coopradar.job.InstitutionRef;
import kr.ac.skuniv.coopradar.job.JobDetail.Closing;
import org.junit.jupiter.api.Test;

/** 3층 판정 규칙(docs/api/README.md '판정', ADR-0012·0024). DB 없이 규칙만 본다. */
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
    void 기관_학년_조건을_못_맞추면_지원_불가() {
        // ADR-0024: 학년·학점·필수 자격증을 못 맞추면 지원 불가(전에는 확인 필요)
        EligibilityJob y34 = judge(job(), me(2, 4, "3.4", false));
        assertThat(y34.verdict()).isEqualTo(Verdict.INELIGIBLE);
        assertThat(line(y34, "학년").result()).isEqualTo(Result.NOT_MET);

        JobRequirement y4 = job(b -> b.gradeRule = "Y4");
        assertThat(line(judge(y4, me(3, 5, "3.4", false)), "학년").result()).isEqualTo(Result.NOT_MET);
        assertThat(judge(y4, me(3, 5, "3.4", false)).verdict()).isEqualTo(Verdict.INELIGIBLE);
        assertThat(line(judge(y4, me(4, 7, "3.4", false)), "학년").requirement()).isEqualTo("4학년");
        assertThat(judge(y4, me(4, 7, "3.4", false)).verdict()).isEqualTo(Verdict.ELIGIBLE);

        JobRequirement graduating = job(b -> b.gradeRule = "GRADUATING");
        ReasonLine notGraduating = line(judge(graduating, me(4, 7, "3.4", false)), "학년");
        assertThat(notGraduating.requirement()).isEqualTo("졸업예정자");
        assertThat(notGraduating.mine()).isEqualTo("졸업예정 아님");
        assertThat(notGraduating.result()).isEqualTo(Result.NOT_MET);
        assertThat(EligibilityRules.blocks(notGraduating)).isTrue();
        assertThat(judge(graduating, me(4, 7, "3.4", true)).verdict()).isEqualTo(Verdict.ELIGIBLE);
    }

    @Test
    void 학점은_하한과_같으면_충족_하한이_없으면_줄이_없다() {
        assertThat(line(judge(job(), me(3, 5, "3.0", false)), "학점").result()).isEqualTo(Result.MET);
        EligibilityJob below = judge(job(b -> b.gpaMin = new BigDecimal("3.5")), me(3, 5, "3.4", false));
        assertThat(line(below, "학점").result()).isEqualTo(Result.NOT_MET);
        assertThat(line(below, "학점").requirement()).isEqualTo("3.5 이상");
        assertThat(below.verdict()).isEqualTo(Verdict.INELIGIBLE);

        EligibilityJob none = judge(job(b -> b.gpaMin = null), me(3, 5, "0.0", false));
        assertThat(none.reasons()).noneMatch(l -> l.item().equals("학점"));
        assertThat(none.verdict()).isEqualTo(Verdict.ELIGIBLE);
    }

    @Test
    void 포트폴리오는_필수면_준비할_서류라_확인_필요_우대면_참고() {
        // ADR-0024: 확인 필요는 준비해서 낼 서류(포트폴리오)와 문서끼리 엇갈린 항목만
        EligibilityJob required = judge(job(b -> b.portfolio = "REQUIRED"), me(3, 5, "3.4", false));
        assertThat(required.verdict()).isEqualTo(Verdict.NEEDS_CHECK);
        assertThat(line(required, "포트폴리오")).extracting(ReasonLine::requirement, ReasonLine::mine, ReasonLine::result)
                .containsExactly("필수", "직접 준비", Result.CHECK);

        EligibilityJob preferred = judge(job(b -> b.portfolio = "PREFERRED"), me(3, 5, "3.4", false));
        assertThat(line(preferred, "포트폴리오").result()).isEqualTo(Result.INFO);
        assertThat(preferred.verdict()).isEqualTo(Verdict.ELIGIBLE);

        // 포트폴리오를 준비해도 학년을 못 맞추면 지원 불가
        EligibilityJob both = judge(job(b -> {
            b.portfolio = "REQUIRED";
            b.gradeRule = "Y4";
        }), me(3, 5, "3.4", false));
        assertThat(both.verdict()).isEqualTo(Verdict.INELIGIBLE);
    }

    @Test
    void 필수_자격증이_없으면_지원_불가_있으면_충족이고_프로필에_없으면_없음으로_본다() {
        // ADR-0021·0024: 필수 자격증이 프로필에 없으면(답하지 않음 포함) 지원 불가
        ReasonCitation plan = new ReasonCitation("OPERATION_PLAN", "(가상)기관 운영계획서", 2, "미용 자격증 소지자");
        Consumer<Builder> beauty = b -> {
            b.certificate = "REQUIRED";
            b.certificateCode = "BEAUTY";
            b.certificateText = "미용 자격증 소지자";
            b.sources = Map.of("CERTIFICATE", plan);
        };
        EligibilityJob lacks = judge(job(beauty), me(3, 5, "3.4", false, List.of()));
        assertThat(lacks.verdict()).isEqualTo(Verdict.INELIGIBLE);
        assertThat(line(lacks, "자격증")).extracting(ReasonLine::layer, ReasonLine::requirement, ReasonLine::mine,
                        ReasonLine::result, ReasonLine::citation)
                .containsExactly(Layer.INSTITUTION, "필수 (미용 자격증 소지자)", "없음", Result.NOT_MET, plan);
        assertThat(EligibilityRules.blocks(line(lacks, "자격증"))).isTrue();

        EligibilityJob other = judge(job(beauty), me(3, 5, "3.4", false, List.of("ACCOUNTING")));
        assertThat(other.verdict()).isEqualTo(Verdict.INELIGIBLE);

        EligibilityJob has = judge(job(beauty), me(3, 5, "3.4", false, List.of("ACCOUNTING", "BEAUTY")));
        assertThat(has.verdict()).isEqualTo(Verdict.ELIGIBLE);
        assertThat(line(has, "자격증")).extracting(ReasonLine::mine, ReasonLine::result).containsExactly("있음", Result.MET);

        EligibilityJob unanswered = judge(job(beauty), me(3, 5, "3.4", false));
        assertThat(unanswered.verdict()).isEqualTo(Verdict.INELIGIBLE);
        assertThat(line(unanswered, "자격증")).extracting(ReasonLine::mine, ReasonLine::result, ReasonLine::citation)
                .containsExactly("없음", Result.NOT_MET, plan);
    }

    @Test
    void 우대_자격증은_있든_없든_참고이고_학년_학점_미충족은_지원_불가() {
        Consumer<Builder> accounting = b -> {
            b.certificate = "PREFERRED";
            b.certificateCode = "ACCOUNTING";
            b.certificateText = "회계관련 자격증";
        };
        EligibilityJob lacks = judge(job(accounting), me(3, 5, "3.4", false, List.of()));
        assertThat(lacks.verdict()).isEqualTo(Verdict.ELIGIBLE);
        assertThat(line(lacks, "자격증")).extracting(ReasonLine::requirement, ReasonLine::mine, ReasonLine::result)
                .containsExactly("우대 (회계관련 자격증)", "없음", Result.INFO);
        assertThat(line(judge(job(accounting), me(3, 5, "3.4", false, List.of("ACCOUNTING"))), "자격증").mine())
                .isEqualTo("있음");
        assertThat(line(judge(job(accounting), me(3, 5, "3.4", false)), "자격증").mine()).isEqualTo("없음");

        EligibilityJob lowGpa = judge(job(b -> b.gradeRule = "Y4"), me(3, 5, "2.0", false, List.of()));
        assertThat(lowGpa.verdict()).isEqualTo(Verdict.INELIGIBLE);
        assertThat(lowGpa.reasons()).filteredOn(EligibilityRules::blocks).extracting(ReasonLine::item)
                .containsExactly("학년", "학점");
    }

    @Test
    void 자격증_칸에_알림이_걸리면_없어도_확인_필요이고_근거는_남는다() {
        ReasonCitation plan = new ReasonCitation("OPERATION_PLAN", "(가상)기관 운영계획서", 2, "미용 자격증 소지자");
        EligibilityJob r = judge(job(b -> {
            b.certificate = "REQUIRED";
            b.certificateCode = "BEAUTY";
            b.sources = Map.of("CERTIFICATE", plan);
            b.alerts = List.of(new AlertRef(9, "DOC_INCONSISTENCY", "certificate"));
        }), me(3, 5, "3.4", false, List.of()));
        assertThat(r.verdict()).isEqualTo(Verdict.NEEDS_CHECK);
        assertThat(line(r, "자격증")).extracting(ReasonLine::result, ReasonLine::alertId, ReasonLine::citation)
                .containsExactly(Result.CHECK, 9, plan);
    }

    @Test
    void 판정_항목_알림은_그_항목_행을_확인_필요로_바꾸고_나머지_알림은_판정을_바꾸지_않는다() {
        // ADR-0016: 학년·학점·포트폴리오·자격증 알림만 판정에 들어간다. 선호 전공·기간 알림은 alertCount로만 보인다
        EligibilityJob ignored = judge(job(b -> b.alerts = List.of(
                new AlertRef(4, "LIST_MISMATCH", "majorRequirement"),
                new AlertRef(5, "LIST_MISMATCH", "period"))), me(3, 5, "3.4", false));
        assertThat(ignored.verdict()).isEqualTo(Verdict.ELIGIBLE);
        assertThat(ignored.reasons()).allSatisfy(l -> assertThat(l.alertId()).isNull());

        EligibilityJob r = judge(job(b -> b.alerts = List.of(
                new AlertRef(6, "DOC_INCONSISTENCY", "gpaRequirement"))), me(3, 5, "3.4", false));
        assertThat(r.verdict()).isEqualTo(Verdict.NEEDS_CHECK);
        ReasonLine gpa = line(r, "학점");
        assertThat(gpa.result()).isEqualTo(Result.CHECK);
        assertThat(gpa.alertId()).isEqualTo(6);
        assertThat(gpa.requirement()).isEqualTo("3.0 이상"); // 요건·내 값은 그대로
        assertThat(gpa.mine()).isEqualTo("3.4");
        assertThat(r.reasons()).filteredOn(l -> l.item().equals("학점")).hasSize(1);

        // 행이 없던 항목(포트폴리오 없음)은 새로 만든다
        EligibilityJob added = judge(job(b -> b.alerts = List.of(
                new AlertRef(7, "LIST_MISMATCH", "portfolio"))), me(3, 5, "3.4", false));
        ReasonLine portfolio = line(added, "포트폴리오");
        assertThat(portfolio.result()).isEqualTo(Result.CHECK);
        assertThat(portfolio.alertId()).isEqualTo(7);
        assertThat(portfolio.requirement()).isEqualTo("참여기관 리스트와 운영계획서가 다르게 적혀 있음");
        assertThat(portfolio.mine()).isEqualTo("—");
    }

    @Test
    void 판정_행에_마감과_직무_알림_수를_그대로_싣는다() {
        EligibilityJob r = judge(job(b -> {
            b.closing = new Closing(LocalDate.of(2026, 7, 18), "CENTER_CLOSED", false);
            b.alertCount = 2;
        }), me(3, 5, "3.4", false));
        assertThat(r.closing().closesOn()).hasToString("2026-07-18");
        assertThat(r.alertCount()).isEqualTo(2);
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
        return me(grade, semesters, gpa, graduating, null);
    }

    private static ProfileInput me(int grade, int semesters, String gpa, boolean graduating, List<String> certificates) {
        return new ProfileInput(DEPT, grade, semesters, new BigDecimal(gpa), graduating, null, null, certificates);
    }

    private static JobRequirement job() {
        return job(b -> {
        });
    }

    private static JobRequirement job(Consumer<Builder> change) {
        Builder b = new Builder();
        change.accept(b);
        return new JobRequirement(101, 1, "(가상)마케팅", "(가상)마케팅팀", new InstitutionRef(1, "(가상)기관"), b.course,
                b.gradeRule, b.gpaMin, b.portfolio, b.certificate, b.certificateText, b.certificateCode,
                b.sources, "미용예술대학", b.majorOpen,
                b.majorDepartmentIds, b.alerts, b.closing, b.alertCount);
    }

    private static final class Builder {
        String course = "SEMESTER";
        String gradeRule = "Y3_4";
        BigDecimal gpaMin = new BigDecimal("3.0");
        String portfolio = "NONE";
        String certificate = "NONE";
        String certificateText = null;
        String certificateCode = null;
        Map<String, ReasonCitation> sources = Map.of();
        boolean majorOpen = false;
        Set<Integer> majorDepartmentIds = Set.of(DEPT);
        List<AlertRef> alerts = List.of();
        Closing closing = new Closing(null, null, false);
        int alertCount = 0;
    }
}
