package kr.ac.skuniv.coopradar.recommend;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kr.ac.skuniv.coopradar.eligibility.EligibilityRules;
import kr.ac.skuniv.coopradar.eligibility.EligibilityService.Judged;
import kr.ac.skuniv.coopradar.eligibility.JobRequirement;
import kr.ac.skuniv.coopradar.eligibility.ProfileInput;
import kr.ac.skuniv.coopradar.job.InstitutionRef;
import kr.ac.skuniv.coopradar.job.JobDetail.Closing;
import kr.ac.skuniv.coopradar.job.Stipend;
import kr.ac.skuniv.coopradar.recommend.FitScorer.Features;
import kr.ac.skuniv.coopradar.recommend.FitScorer.Scored;
import kr.ac.skuniv.coopradar.recommend.RecommendDtos.Fit;
import org.junit.jupiter.api.Test;

/** 적합도 점수·등급(ADR-0018). DB 없이 본다. 학생 학과는 43. 기본 이유 문장은 ReasonTemplatesTest. */
class FitScorerTest {

    private static final int DEPT = 43;

    @Test
    void 선호_전공이_맞는_직무가_관심_키워드만_맞는_직무보다_먼저다() {
        var jobs = List.of(
                job(1, 1, Set.of(), "Y3_4"),       // 전공 밖, 관심과 같은 글
                job(2, 2, Set.of(DEPT), "Y3_4"));  // 전공 일치, 관심과 무관한 글
        Map<Integer, Features> f = Map.of(
                1, features("뷰티 브랜드 SNS 마케팅 콘텐츠 기획", "HIRING", 2_156_880),
                2, features("물류 창고 재고 관리", "EXPERIENCE", 1_617_660));
        List<Scored> s = FitScorer.score(judge(jobs), f, "뷰티 브랜드 SNS 마케팅");
        assertThat(s).extracting(Scored::jobId).containsExactly(2, 1);
        assertThat(s.get(0).fit()).isEqualTo(Fit.MEDIUM); // 전공은 맞지만 관심과 멀다
        assertThat(s.get(1).fit()).isEqualTo(Fit.MEDIUM); // 관심은 가깝지만 전공 밖
        assertThat(s.get(1).interest()).isEqualTo(1.0);
        assertThat(FitScorer.interestClose(s.get(1).interest())).isTrue();
        assertThat(FitScorer.interestClose(s.get(0).interest())).isFalse();
    }

    @Test
    void 전공이_맞고_관심이_가까우면_HIGH_관심_문장이_없으면_전공만으로_HIGH() {
        var jobs = List.of(job(1, 1, Set.of(DEPT), "Y3_4"), job(2, 2, Set.of(DEPT), "Y3_4"));
        Map<Integer, Features> f = Map.of(
                1, features("뷰티 브랜드 SNS 마케팅 운영", "EXPERIENCE", 1_617_660),
                2, features("회계 전표 정리", "EXPERIENCE", 1_617_660));
        List<Scored> withInterest = FitScorer.score(judge(jobs), f, "뷰티 SNS 마케팅");
        assertThat(withInterest).extracting(Scored::jobId).containsExactly(1, 2);
        assertThat(withInterest).extracting(Scored::fit).containsExactly(Fit.HIGH, Fit.MEDIUM);

        List<Scored> noInterest = FitScorer.score(judge(jobs), f, "  ");
        assertThat(noInterest).extracting(Scored::fit).containsOnly(Fit.HIGH);
        assertThat(noInterest).extracting(Scored::interest).containsOnly(0.0);
        assertThat(noInterest).extracting(Scored::jobId).containsExactly(1, 2); // 같은 점수면 리스트 순번
    }

    @Test
    void 선호_전공도_관심도_맞지_않으면_추천할_이유가_없다() {
        // ADR-0022: 점수가 있어도 이유가 없는 직무는 추천하지 않는다
        var jobs = List.of(job(1, 1, Set.of(), "Y3_4"), job(2, 2, Set.of(), "Y3_4"), job(3, 3, Set.of(DEPT), "Y3_4"));
        Map<Integer, Features> f = Map.of(
                1, features("소프트웨어 개발 및 시스템 구축", "EXPERIENCE", 1_617_660),
                2, features("미용 시술 보조와 고객 응대", "HIRING", 2_156_880),
                3, features("물류 창고 재고 관리", "EXPERIENCE", 1_617_660));
        List<Scored> s = FitScorer.score(judge(jobs), f, "소프트웨어 개발");
        assertThat(s).filteredOn(Scored::relevant).extracting(Scored::jobId).containsExactlyInAnyOrder(1, 3);
        assertThat(s).filteredOn(x -> x.jobId() == 2).singleElement()
                .satisfies(x -> assertThat(x.interestMatch()).isFalse());
        // 관심 문장이 없으면 선호 전공이 맞는 직무만
        assertThat(FitScorer.score(judge(jobs), f, null)).filteredOn(Scored::relevant).extracting(Scored::jobId)
                .containsExactly(3);
    }

    @Test
    void 관심_겹침은_원_코사인_하한과_0점05_또는_최댓값의_절반으로_본다() {
        assertThat(FitScorer.interestMatch(0.041, 1.0)).isTrue();   // '백엔드 개발자' ↔ 소프트웨어 개발(가장 많이 겹침)
        assertThat(FitScorer.interestMatch(0.016, 0.40)).isFalse(); // '개발' 한 낱말만 겹친 마케팅 직무
        assertThat(FitScorer.interestMatch(0.06, 0.30)).isTrue();   // 많이 겹치는 직무가 따로 있어도 0.05 이상이면
        assertThat(FitScorer.interestMatch(0.015, 1.0)).isFalse();  // 1등이어도 하한 아래면 우연
        assertThat(FitScorer.interestMatch(0.0, 0.0)).isFalse();
    }

    @Test
    void 같은_조건이면_지원_가능_지원비_채용연계_순으로_가점() {
        var jobs = List.of(
                job(1, 1, Set.of(), "Y4"),      // 3학년이라 확인 필요
                job(2, 2, Set.of(), "Y3_4"),    // 지원 가능
                job(3, 3, Set.of(), "Y3_4"));   // 지원 가능 + 지원비 100%
        Map<Integer, Features> f = Map.of(
                1, features("가", "HIRING", 2_156_880),
                2, features("가", "EXPERIENCE", 1_617_660),
                3, features("가", "EXPERIENCE", 2_156_880));
        assertThat(FitScorer.score(judge(jobs), f, null)).extracting(Scored::jobId).containsExactly(3, 1, 2);
    }

    @Test
    void 지원비_점수는_최저임금_75퍼센트에서_0_100퍼센트에서_1() {
        assertThat(FitScorer.stipendScore(Stipend.of("MONTHLY", 1_617_660))).isEqualTo(0.0);
        assertThat(FitScorer.stipendScore(Stipend.of("MONTHLY", 2_156_880))).isEqualTo(1.0);
        assertThat(FitScorer.stipendScore(Stipend.of("MONTHLY", 3_000_000))).isEqualTo(1.0);
        assertThat(FitScorer.stipendScore(Stipend.of("HOURLY", 9_030))).isEqualTo(0.5);
        assertThat(FitScorer.stipendScore(Stipend.of("UNSPECIFIED", null))).isEqualTo(0.0);
    }

    // ───────── 도우미 ─────────

    private static List<Judged> judge(List<JobRequirement> jobs) {
        ProfileInput me = new ProfileInput(DEPT, 3, 5, new BigDecimal("3.4"), false, null, null, null);
        return jobs.stream().map(r -> new Judged(r, EligibilityRules.judge(r, me, "메이크업디자인학과"))).toList();
    }

    private static JobRequirement job(int id, int seq, Set<Integer> majorDepartments, String gradeRule) {
        return new JobRequirement(id, seq, "(가상)직무" + id, "(가상)팀", new InstitutionRef(id, "(가상)기관" + id), "SEMESTER",
                gradeRule, null, "NONE", "NONE", null, null, null, "(가상)전공", false, majorDepartments, List.of(),
                new Closing(null, null, false), 0);
    }

    private static Features features(String text, String jobType, int monthly) {
        return new Features(text, jobType, Stipend.of("MONTHLY", monthly));
    }
}
