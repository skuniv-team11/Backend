package kr.ac.skuniv.coopradar.internship;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import kr.ac.skuniv.coopradar.eligibility.EligibilityDtos.Verdict;
import kr.ac.skuniv.coopradar.internship.ApplicationRepository.ApprovalRow;
import kr.ac.skuniv.coopradar.internship.ApplicationRepository.CloseRow;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.Applicant;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.ApprovalKind;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.ApprovalStatus;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.Check;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.CloseItem;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.Item;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.NextKind;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.Pick;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.Resume;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.StageState;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.TimelineStage;
import kr.ac.skuniv.coopradar.job.InstitutionRef;
import kr.ac.skuniv.coopradar.reference.ReferenceDtos.Phase;
import kr.ac.skuniv.coopradar.reference.ReferenceDtos.Stage;
import kr.ac.skuniv.coopradar.reference.ReferenceDtos.StageInfo;
import org.junit.jupiter.api.Test;

/** 현장실습 진행 규칙(DB 없이, ADR-0033): 일정 단계 · 주차 계획 칸 · 빠진 칸 · 승인 해시 · 기관 이름 경고 · CSV. */
class InternshipUnitTest {

    /** 2026-2 학생 모집안내 일정(curated/round.json과 같은 날짜). */
    static final List<StageInfo> STAGES = List.of(
            s(Stage.PICK, Phase.APPLY, "2026-07-13", "2026-07-24"), s(Stage.APPLY, Phase.APPLY, "2026-07-13", "2026-07-24"),
            s(Stage.MATCH, Phase.APPLY, "2026-07-27", "2026-07-28"), s(Stage.SELECT, Phase.APPLY, "2026-07-29", "2026-08-05"),
            s(Stage.CONTRACT, Phase.PREPARE, "2026-08-10", "2026-08-28"),
            s(Stage.ORIENTATION, Phase.PREPARE, "2026-08-26", "2026-08-26"),
            s(Stage.PRACTICE, Phase.PRACTICE, "2026-09-01", "2026-12-12"),
            s(Stage.MIDCHECK, Phase.PRACTICE, "2026-10-19", "2026-10-23"), s(Stage.CLOSE, Phase.CLOSE, "2026-12-12", null),
            s(Stage.DEBRIEF, Phase.CLOSE, "2026-12-17", "2026-12-17"), s(Stage.CREDIT, Phase.CLOSE, null, null));

    static StageInfo s(Stage code, Phase phase, String start, String end) {
        return new StageInfo(code, phase, start == null ? null : LocalDate.parse(start),
                end == null ? null : LocalDate.parse(end), true, "테스트");
    }

    static TimelineService.Stages at(String day) {
        return TimelineService.stages(STAGES, LocalDate.parse(day));
    }

    @Test
    void 지금_단계는_진행_중인_것_중_가장_뒤_없으면_다음에_올_것() {
        assertThat(at("2026-07-01").now()).isEqualTo(Stage.PICK);
        assertThat(at("2026-07-23").now()).isEqualTo(Stage.APPLY);
        assertThat(at("2026-07-23").next()).satisfies(n -> {
            assertThat(n.code()).isEqualTo(Stage.APPLY);
            assertThat(n.kind()).isEqualTo(NextKind.END);
            assertThat(n.days()).isEqualTo(1);
        });
        // 신청 마감 뒤 매칭 전 주말: 다음에 올 매칭
        assertThat(at("2026-07-25").now()).isEqualTo(Stage.MATCH);
        assertThat(at("2026-07-25").next().kind()).isEqualTo(NextKind.START);
        // 협약 기간 안의 사전교육 날, 그 전에는 사전교육이 가장 가까운 시작
        assertThat(at("2026-08-26").now()).isEqualTo(Stage.ORIENTATION);
        assertThat(at("2026-08-20").next().code()).isEqualTo(Stage.ORIENTATION);
        // 실습 중: 중간점검까지 D-5, 중간점검이 끝나면 다시 실습
        assertThat(at("2026-10-14").next().code()).isEqualTo(Stage.MIDCHECK);
        assertThat(at("2026-10-14").next().days()).isEqualTo(5);
        assertThat(at("2026-10-20").now()).isEqualTo(Stage.MIDCHECK);
        TimelineService.Stages after = at("2026-10-26");
        assertThat(after.now()).isEqualTo(Stage.PRACTICE);
        assertThat(after.list().get(7).state()).isEqualTo(StageState.DONE);
        // 간담회 날 · 그 뒤는 날짜 없는 학점 인정
        assertThat(at("2026-12-17").now()).isEqualTo(Stage.DEBRIEF);
        assertThat(at("2026-12-18").now()).isEqualTo(Stage.CREDIT);
        assertThat(at("2026-12-18").list()).extracting(TimelineStage::state).containsOnly(StageState.DONE, StageState.NOW);
    }

    @Test
    void 주차_계획_칸을_주로_읽는다() {
        assertThat(TimelineService.weeksOf("7~8주차", 15)).containsExactly(7, 8);
        assertThat(TimelineService.weeksOf("3-5주차", 15)).containsExactly(3, 4, 5);
        assertThat(TimelineService.weeksOf("1,3주차", 15)).containsExactly(1, 3);
        assertThat(TimelineService.weeksOf("10주차~", 12)).containsExactly(10, 11, 12);
        assertThat(TimelineService.weeksOf("모든 주차", 3)).containsExactly(1, 2, 3);
        assertThat(TimelineService.weeksOf("15주차", 15)).containsExactly(15);
        assertThat(TimelineService.weeksOf("이후", 15)).isEmpty();
    }

    @Test
    void 빠진_칸과_지망_판정을_본다() {
        Applicant a = new Applicant("예시 학생", "Yesi", LocalDate.of(2004, 3, 15), "F", "010-0000-0000", null, "서울",
                "2023000000", null);
        InstitutionRef inst = new InstitutionRef(7, "주식회사 소서");
        List<Pick> picks = List.of(new Pick(1, 122, "국내 마케팅", "마케팅팀", inst, Verdict.ELIGIBLE, false),
                new Pick(2, 120, "AMD", "세일즈팀", inst, Verdict.NEEDS_CHECK, false));
        String essay = "가".repeat(300);
        List<Check> ok = ApplicationViews.checklist(true, picks, a, true, List.of(essay, essay, essay, essay), true, true,
                "예시 학생", ApprovalStatus.APPROVED, 300);
        assertThat(ok).allMatch(Check::done);
        List<Check> bad = ApplicationViews.checklist(false, List.of(new Pick(2, 120, "AMD", "세일즈팀", inst, Verdict.ELIGIBLE,
                        false)), new Applicant("예시", null, null, null, null, null, null, null, null), false,
                List.of(essay, "짧음", essay, essay), true, false, " ", ApprovalStatus.STALE, 300);
        assertThat(bad).filteredOn(c -> !c.done()).extracting(Check::item).containsExactly(Item.PROFILE, Item.PICKS,
                Item.APPLICANT, Item.PLEDGE, Item.ESSAYS, Item.CONSENTS, Item.SIGNATURE, Item.APPROVAL);
        // 지원 불가·마감·판정 없음(프로필 없음)은 1~3지망을 막는다
        assertThat(ApplicationViews.checklist(true, List.of(new Pick(1, 1, "t", "t", inst, Verdict.INELIGIBLE, false)), a,
                true, List.of(essay, essay, essay, essay), true, true, "이름", ApprovalStatus.APPROVED, 300).get(1).done())
                .isFalse();
        assertThat(ApplicationViews.checklist(true, List.of(new Pick(1, 1, "t", "t", inst, null, false)), a,
                true, List.of(essay, essay, essay, essay), true, true, "이름", ApprovalStatus.APPROVED, 300).get(1).done())
                .isFalse();
    }

    @Test
    void 승인은_내용_해시로_묶고_바뀌면_STALE() {
        Applicant a = new Applicant("예시", null, null, null, null, null, null, null, null);
        List<String> essays = List.of("1", "2", "3", "4");
        String h1 = ApplicationViews.contentHash(a, Resume.EMPTY, essays, true, true, true, "예시", List.of(122, 120));
        assertThat(h1).hasSize(64).isEqualTo(
                ApplicationViews.contentHash(a, Resume.EMPTY, essays, true, true, true, "예시", List.of(122, 120)));
        String h2 = ApplicationViews.contentHash(a, Resume.EMPTY, essays, true, true, true, "예시", List.of(120, 122));
        assertThat(h2).isNotEqualTo(h1);
        ApprovalRow approved = new ApprovalRow(1, ApprovalKind.APPLICATION, "t", h1, null, java.time.OffsetDateTime.now());
        assertThat(ApplicationViews.approvalStatus(approved, h1)).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(ApplicationViews.approvalStatus(approved, h2)).isEqualTo(ApprovalStatus.STALE);
        assertThat(ApplicationViews.approvalStatus(null, h1)).isEqualTo(ApprovalStatus.NONE);
        assertThat(ApplicationViews.token()).matches("^[0-9a-f]{32}$");
    }

    @Test
    void 자기소개서의_기관_이름은_법인_표기와_괄호를_빼고_찾는다() {
        assertThat(ApplicationViews.coreName("(주) 세정 (웰메이드, 인디안, 디디에두보)")).isEqualTo("세정");
        assertThat(ApplicationViews.coreName("주식회사 소서")).isEqualTo("소서");
        var warnings = ApplicationViews.essayWarnings(List.of("세정 매장에서 일했습니다", "", "소서와 세정", ""),
                List.of("(주) 세정 (웰메이드)", "주식회사 소서", "주식회사 소서"));
        assertThat(warnings).extracting(w -> w.index()).containsExactly(0, 2);
        assertThat(warnings.get(1).institutions()).containsExactly("세정", "소서");
    }

    @Test
    void 마무리에서_빠진_것과_CSV_칸() {
        CloseRow none = CloseRow.empty(1);
        assertThat(CenterFlowService.missing(none, false)).containsExactly(CloseItem.REPORT, CloseItem.CREDIT,
                CloseItem.SURVEY, CloseItem.EVALUATION, CloseItem.ATTENDANCE, CloseItem.APPROVAL);
        var now = java.time.OffsetDateTime.now();
        assertThat(CenterFlowService.missing(new CloseRow(1, now, now, now, now, now, null), true)).isEmpty();
        assertThat(CenterFlowService.csv("=HYPERLINK(\"x\")")).isEqualTo("\"'=HYPERLINK(\"\"x\"\")\"");
        assertThat(CenterFlowService.csv(null)).isEqualTo("\"\"");
    }
}
