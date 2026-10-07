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
import kr.ac.skuniv.coopradar.recommend.FitScorer.Interest;
import kr.ac.skuniv.coopradar.recommend.FitScorer.MajorTier;
import kr.ac.skuniv.coopradar.recommend.FitScorer.Scored;
import kr.ac.skuniv.coopradar.recommend.RecommendDtos.Fit;
import org.junit.jupiter.api.Test;

/** 적합도 점수·등급·추천할 이유(ADR-0026). DB 없이 본다. 학생 학과는 43. 기본 이유 문장은 EvidenceTest. */
class FitScorerTest {

    private static final int DEPT = 43;
    private static final Set<Integer> GROUP = Set.of(DEPT, 44, 45);   // 계열·단과대 표기(학과 3개 이상)
    private static final String ASKED = "뷰티 브랜드 SNS 마케팅";
    private static final String CLOSE = "뷰티 브랜드 SNS 마케팅 콘텐츠 기획";
    private static final String FAR = "물류 창고 재고 관리";

    @Test
    void 관심을_안_적으면_학과_지명_계열_전공_무관_순이고_선호_전공_밖은_추천하지_않는다() {
        var jobs = List.of(
                job(1, 1, Set.of(), true, "Y3_4", "NONE"),    // 전공 무관
                job(2, 2, GROUP, false, "Y3_4", "NONE"),      // 계열
                job(3, 3, Set.of(DEPT), false, "Y3_4", "NONE"),  // 학과 지명
                job(4, 4, Set.of(1), false, "Y3_4", "NONE"));    // 선호 전공 밖
        Map<Integer, Features> f = Map.of(
                1, features(FAR, Set.of()), 2, features(FAR, Set.of()), 3, features(FAR, Set.of(DEPT)), 4, features(FAR, Set.of()));
        List<Scored> s = FitScorer.score(judge(jobs), f, null, DEPT);
        assertThat(s).extracting(Scored::jobId).containsExactly(3, 2, 1, 4);
        assertThat(s).extracting(Scored::major).containsExactly(MajorTier.DIRECT, MajorTier.GROUP, MajorTier.OPEN,
                MajorTier.NONE);
        // 관심 문장이 없으면 전공 무관도 (약한) 추천 이유다 — 학과 지명·계열 뒤에 온다
        assertThat(s).filteredOn(Scored::relevant).extracting(Scored::jobId).containsExactly(3, 2, 1);
        // 맞는 근거(학과 지명·계열)가 있고 어긋나는 근거가 없으면 높음. 전공 무관은 맞는 근거가 아니라 보통
        assertThat(s).extracting(Scored::fit).containsExactly(Fit.HIGH, Fit.HIGH, Fit.MEDIUM, Fit.MEDIUM);
    }

    @Test
    void 관심을_적으면_전공과_관심이_어긋나지_않아야_높음이고_높음이_먼저다() {
        var jobs = List.of(
                job(1, 1, Set.of(DEPT), false, "Y3_4", "NONE"),  // 학과 지명 + 관심 많이 겹침 → 높음
                job(2, 2, Set.of(1), false, "Y3_4", "NONE"),     // 선호 전공 밖 + 관심 많이 겹침 → 보통
                job(3, 3, GROUP, false, "Y3_4", "NONE"),         // 계열 + 관심과 멂 → 보통
                job(4, 4, Set.of(), true, "Y3_4", "NONE"),       // 전공 무관 + 관심 많이 겹침 → 높음
                job(5, 5, Set.of(), true, "Y3_4", "NONE"));      // 전공 무관 + 관심과 멂 → 추천할 이유 없음(관심을 적었으니)
        Map<Integer, Features> f = Map.of(
                1, features(CLOSE, Set.of(DEPT)), 2, features(CLOSE, Set.of()), 3, features(FAR, Set.of()),
                4, features(CLOSE, Set.of()), 5, features(FAR, Set.of()));
        List<Scored> s = FitScorer.score(judge(jobs), f, ASKED, DEPT);
        assertThat(s).extracting(Scored::jobId, Scored::fit).startsWith(
                org.assertj.core.groups.Tuple.tuple(1, Fit.HIGH), org.assertj.core.groups.Tuple.tuple(4, Fit.HIGH));
        assertThat(s).filteredOn(x -> x.jobId() == 2).singleElement()
                .satisfies(x -> assertThat(x.interestFit()).isEqualTo(Interest.CLOSE))
                .satisfies(x -> assertThat(x.fit()).isEqualTo(Fit.MEDIUM));
        assertThat(s).filteredOn(x -> x.jobId() == 3).singleElement()
                .satisfies(x -> assertThat(x.interestFit()).isEqualTo(Interest.NONE))
                .satisfies(x -> assertThat(x.fit()).isEqualTo(Fit.MEDIUM))
                .satisfies(x -> assertThat(x.relevant()).isTrue());
        assertThat(s).filteredOn(Scored::relevant).extracting(Scored::jobId).doesNotContain(5);
    }

    @Test
    void 가까운_전공은_계열과_전공_무관_사이이고_관심이_많이_겹칠_때만_높음() {
        // ADR-0028: 선호 전공 학과(1)가 내 가까운 학과면 NEAR. 추천 이유는 되지만 회사가 직접 적은 전공은 아니라서
        // 관심을 안 적으면 보통, 관심이 많이 겹치면 높음
        var jobs = List.of(
                job(1, 1, GROUP, false, "Y3_4", "NONE"),          // 계열
                job(2, 2, Set.of(1), false, "Y3_4", "NONE"),      // 가까운 전공
                job(3, 3, Set.of(), true, "Y3_4", "NONE"));       // 전공 무관
        Map<Integer, Features> far = Map.of(1, features(FAR, Set.of()), 2, features(FAR, Set.of(1)), 3, features(FAR, Set.of()));
        List<Scored> none = FitScorer.score(judgeNear(jobs, Set.of(1)), far, null, DEPT);
        assertThat(none).extracting(Scored::jobId).containsExactly(1, 2, 3);
        assertThat(none).extracting(Scored::major).containsExactly(MajorTier.GROUP, MajorTier.NEAR, MajorTier.OPEN);
        assertThat(none).extracting(Scored::fit).containsExactly(Fit.HIGH, Fit.MEDIUM, Fit.MEDIUM);
        assertThat(none).allMatch(Scored::relevant);

        Map<Integer, Features> close = Map.of(1, features(FAR, Set.of()), 2, features(CLOSE, Set.of(1)), 3, features(FAR, Set.of()));
        Scored near = FitScorer.score(judgeNear(jobs, Set.of(1)), close, ASKED, DEPT).stream()
                .filter(s -> s.jobId() == 2).findFirst().orElseThrow();
        assertThat(near.interestFit()).isEqualTo(Interest.CLOSE);
        assertThat(near.fit()).isEqualTo(Fit.HIGH);
    }

    @Test
    void 학과_지명만_맞는_직무와_관심만_딱_맞는_직무는_같은_무게다() {
        // 회사가 내 학과를 콕 집은 것(3)과 내가 적은 관심과 가장 많이 겹치는 것(3 × 1.0)을 같게 본다 — 같으면 리스트 순번
        var jobs = List.of(job(1, 1, Set.of(1), false, "Y3_4", "NONE"), job(2, 2, Set.of(DEPT), false, "Y3_4", "NONE"));
        Map<Integer, Features> f = Map.of(1, features(CLOSE, Set.of()), 2, features(FAR, Set.of(DEPT)));
        List<Scored> s = FitScorer.score(judge(jobs), f, ASKED, DEPT);
        assertThat(s).extracting(Scored::jobId).containsExactly(1, 2);
        assertThat(s.get(0).score()).isEqualTo(s.get(1).score());
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
                job(1, 1, Set.of(), false, "Y3_4", "REQUIRED"),  // 포트폴리오 필수라 확인 필요
                job(2, 2, Set.of(), false, "Y3_4", "NONE"),      // 지원 가능
                job(3, 3, Set.of(), false, "Y3_4", "NONE"));     // 지원 가능 + 지원비 100%
        Map<Integer, Features> f = Map.of(
                1, new Features("가", "HIRING", Stipend.of("MONTHLY", 2_156_880), Set.of()),
                2, new Features("가", "EXPERIENCE", Stipend.of("MONTHLY", 1_617_660), Set.of()),
                3, new Features("가", "EXPERIENCE", Stipend.of("MONTHLY", 2_156_880), Set.of()));
        assertThat(FitScorer.score(judge(jobs), f, null, DEPT)).extracting(Scored::jobId).containsExactly(3, 1, 2);
    }

    @Test
    void 지원_불가는_빼되_관심_유사도의_기준은_회차_직무_전부다() {
        // ADR-0024: 가장 많이 겹친 직무(1)가 학년 때문에 지원 불가여도, 조금 겹친 직무(2)가 '가장 가까운 직무'가 되지 않는다
        var jobs = List.of(job(1, 1, Set.of(), false, "Y4", "NONE"), job(2, 2, Set.of(), false, "Y3_4", "NONE"));
        Map<Integer, Features> f = Map.of(
                1, features("백엔드 서버 개발과 데이터베이스 설계", Set.of()),
                2, features("브랜드 마케팅 콘텐츠 개발 지원과 매장 운영", Set.of()));
        List<Scored> s = FitScorer.score(judge(jobs), f, "백엔드 개발자", DEPT);
        assertThat(s).extracting(Scored::jobId).containsExactly(2);
        assertThat(s.getFirst().interest()).isLessThan(FitScorer.HIGH_INTEREST);
        assertThat(s.getFirst().relevant()).isFalse();
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

    private static List<Judged> judgeNear(List<JobRequirement> jobs, Set<Integer> near) {
        ProfileInput me = new ProfileInput(DEPT, 3, 5, new BigDecimal("3.4"), false, null, null, null);
        return jobs.stream().map(r -> new Judged(r, EligibilityRules.judge(r, me, "메이크업디자인학과", near))).toList();
    }

    private static JobRequirement job(int id, int seq, Set<Integer> majorDepartments, boolean majorOpen, String gradeRule,
                                      String portfolio) {
        return new JobRequirement(id, seq, "(가상)직무" + id, "(가상)팀", new InstitutionRef(id, "(가상)기관" + id), "SEMESTER",
                gradeRule, null, portfolio, "NONE", null, null, Map.of(), "(가상)전공", majorOpen, majorDepartments, List.of(),
                new Closing(null, null, false), 0);
    }

    private static Features features(String text, Set<Integer> direct) {
        return new Features(text, "EXPERIENCE", Stipend.of("MONTHLY", 1_617_660), direct);
    }
}
