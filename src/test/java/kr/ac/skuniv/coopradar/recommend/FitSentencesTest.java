package kr.ac.skuniv.coopradar.recommend;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import kr.ac.skuniv.coopradar.eligibility.EligibilityDtos.MajorMatch;
import kr.ac.skuniv.coopradar.eligibility.EligibilityDtos.Verdict;
import kr.ac.skuniv.coopradar.eligibility.EligibilityRules;
import kr.ac.skuniv.coopradar.eligibility.EligibilityService.Judged;
import kr.ac.skuniv.coopradar.eligibility.JobRequirement;
import kr.ac.skuniv.coopradar.eligibility.JobRequirement.AlertRef;
import kr.ac.skuniv.coopradar.eligibility.ProfileInput;
import kr.ac.skuniv.coopradar.job.InstitutionRef;
import kr.ac.skuniv.coopradar.job.JobDetail.Closing;
import kr.ac.skuniv.coopradar.recommend.FitSentences.Josa;
import org.junit.jupiter.api.Test;

/** '왜 나에게 맞는지'를 내 값으로 말하는 규칙 문장(ADR-0024). 판정 이유 줄 → 문장. */
class FitSentencesTest {

    private static final Map<String, String> LABELS = Map.of("BEAUTY", "미용 자격증·면허증");

    @Test
    void 지원_가능은_갖춘_조건을_내_값과_함께_말한다() {
        assertThat(lead(job(b -> b.gpaMin = null), me(3, 5, "3.4", false, null)))
                .isEqualTo("3학년이라 지원 조건(3·4학년)을 모두 갖췄어요.");
        assertThat(lead(job(b -> b.gpaMin = new BigDecimal("3.0")), me(3, 5, "3.4", false, null)))
                .isEqualTo("3학년·학점 3.4라 지원 조건(3·4학년, 학점 3.0 이상)을 모두 갖췄어요.");
        assertThat(lead(job(b -> {
            b.gradeRule = "GRADUATING";
            b.gpaMin = null;
        }), me(4, 7, "3.0", true, null)))
                .isEqualTo("졸업 예정이라 지원 조건(졸업예정자)을 모두 갖췄어요.");
        assertThat(lead(job(this::beauty), me(4, 7, "3.4", false, List.of("BEAUTY"))))
                .isEqualTo("4학년이고 미용 자격증·면허증이 있어 지원 조건(3·4학년, 미용 자격증·면허증)을 모두 갖췄어요.");
    }

    @Test
    void 지원_불가는_못_맞춘_조건을_하나씩_내_값과_함께_말한다() {
        assertThat(lead(job(b -> b.gradeRule = "Y4"), me(3, 5, "3.4", false, null)))
                .isEqualTo("지금은 지원할 수 없어요. 4학년만 지원할 수 있는데 지금 3학년이에요.");
        assertThat(lead(job(b -> b.gpaMin = new BigDecimal("3.5")), me(3, 5, "3.4", false, null)))
                .isEqualTo("지금은 지원할 수 없어요. 학점 3.5 이상이어야 하는데 지금 3.4예요.");
        assertThat(lead(job(b -> b.gradeRule = "GRADUATING"), me(4, 7, "3.4", false, null)))
                .isEqualTo("지금은 지원할 수 없어요. 졸업 예정자만 지원할 수 있어요.");
        assertThat(lead(job(b -> b.gpaMin = null), me(2, 3, "3.4", false, null)))
                .isEqualTo("지금은 지원할 수 없어요. 현장실습은 4학기 이상 마쳐야 지원할 수 있는데 지금 3학기를 마쳤어요. "
                        + "3·4학년만 지원할 수 있는데 지금 2학년이에요.");
        // 자격증은 프로필에 없으면(답하지 않음 포함) 없음이다
        assertThat(lead(job(this::beauty), me(3, 5, "3.4", false, null)))
                .isEqualTo("지금은 지원할 수 없어요. 미용 자격증·면허증이 있어야 하는데 프로필에 없어요.");
    }

    @Test
    void 확인_필요는_챙길_것을_말하고_갖춘_조건도_붙인다() {
        assertThat(lead(job(b -> b.portfolio = "REQUIRED"), me(3, 5, "3.4", false, null)))
                .isEqualTo("포트폴리오만 준비해서 내면 지원할 수 있어요. 3학년·학점 3.4라 나머지 조건(3·4학년, 학점 3.0 이상)은 갖췄어요.");
        // 문서끼리 학점 요건이 엇갈리면 학점은 갖춘 조건에서 빠지고 센터 확인을 말한다
        assertThat(lead(job(b -> b.alerts = List.of(new AlertRef(6, "DOC_INCONSISTENCY", "gpaRequirement"))),
                me(3, 5, "3.4", false, null)))
                .isEqualTo("지원하기 전에 챙길 것이 있어요. 공고 문서끼리 학점 조건이 다르게 적혀 있어 현장실습지원센터에 확인이 필요해요. "
                        + "3학년이라 나머지 조건(3·4학년)은 갖췄어요.");
    }

    @Test
    void 전공_문장은_내_학과_이름으로_말하고_선호_전공은_자격이_아니라고_한다() {
        assertThat(FitSentences.major(Verdict.ELIGIBLE, MajorMatch.MATCH, "메이크업디자인학과", "미용예술대학"))
                .isEqualTo("메이크업디자인학과는 회사가 선호하는 전공('미용예술대학')에 들어가요.");
        assertThat(FitSentences.major(Verdict.ELIGIBLE, MajorMatch.MATCH, "광고홍보콘텐츠학과", "광고홍보콘텐츠학과"))
                .isEqualTo("광고홍보콘텐츠학과는 회사가 선호하는 전공이에요.");
        assertThat(FitSentences.major(Verdict.NEEDS_CHECK, MajorMatch.NOT_LISTED, "무대패션전공", null))
                .isEqualTo("무대패션전공은 회사가 선호하는 전공에는 없지만, 선호 전공은 지원 자격과는 상관없어요.");
        assertThat(FitSentences.major(Verdict.ELIGIBLE, MajorMatch.OPEN, "군사학과", null)).isEqualTo(FitSentences.OPEN);
        assertThat(FitSentences.major(Verdict.INELIGIBLE, MajorMatch.MATCH, "군사학과", "사회계열")).isNull();
    }

    @Test
    void 조사는_끝소리를_따른다() {
        assertThat(Josa.eunNeun("군사학과")).isEqualTo("군사학과는");
        assertThat(Josa.eunNeun("무대패션전공")).isEqualTo("무대패션전공은");
        assertThat(Josa.eunNeun("금융정보공학과(이공대학)")).isEqualTo("금융정보공학과(이공대학)는");
        assertThat(Josa.eunNeun("미래융합학부1")).isEqualTo("미래융합학부1은");
        assertThat(Josa.ieyo("3.4")).isEqualTo("3.4예요");
        assertThat(Josa.ieyo("3.0")).isEqualTo("3.0이에요");
        assertThat(Josa.ira("학점 3.6")).isEqualTo("학점 3.6이라");
        assertThat(Josa.iGa("미용 자격증·면허증")).isEqualTo("미용 자격증·면허증이");
    }

    // ───────── 도우미 ─────────

    private void beauty(Builder b) {
        b.certificate = "REQUIRED";
        b.certificateCode = "BEAUTY";
        b.certificateText = "미용 자격증 or 미용 면허증 소지자";
        b.gpaMin = null;
    }

    private static String lead(JobRequirement job, ProfileInput me) {
        return FitSentences.lead(new Judged(job, EligibilityRules.judge(job, me, "메이크업디자인학과")), LABELS);
    }

    private static ProfileInput me(int grade, int semesters, String gpa, boolean graduating, List<String> certificates) {
        return new ProfileInput(43, grade, semesters, new BigDecimal(gpa), graduating, null, null, certificates);
    }

    private static JobRequirement job(Consumer<Builder> change) {
        Builder b = new Builder();
        change.accept(b);
        return new JobRequirement(101, 1, "(가상)마케팅", "(가상)마케팅팀", new InstitutionRef(1, "(가상)기관"), "SEMESTER",
                b.gradeRule, b.gpaMin, b.portfolio, b.certificate, b.certificateText, b.certificateCode, Map.of(),
                "미용예술대학", false, Set.of(43), b.alerts, new Closing(null, null, false), 0);
    }

    private static final class Builder {
        String gradeRule = "Y3_4";
        BigDecimal gpaMin = new BigDecimal("3.0");
        String portfolio = "NONE";
        String certificate = "NONE";
        String certificateText = null;
        String certificateCode = null;
        List<AlertRef> alerts = List.of();
    }
}
