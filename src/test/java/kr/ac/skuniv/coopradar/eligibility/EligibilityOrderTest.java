package kr.ac.skuniv.coopradar.eligibility;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kr.ac.skuniv.coopradar.eligibility.EligibilityService.Judged;
import kr.ac.skuniv.coopradar.job.InstitutionRef;
import kr.ac.skuniv.coopradar.job.JobDetail.Closing;
import kr.ac.skuniv.coopradar.reference.ReferenceDates.AsOf;
import org.junit.jupiter.api.Test;

/** 판정 목록 순서(ADR-0027): 판정 → 모집 중 먼저 → 적합도 순 → 리스트 순번. */
class EligibilityOrderTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 7, 23);
    private static final LocalDate END = LocalDate.of(2026, 7, 24);
    private static final ProfileInput ME = new ProfileInput(43, 3, 5, new BigDecimal("3.4"), false, null, null, List.of());

    @Test
    void 같은_판정_안에서_모집_중은_적합도_순이고_마감은_아래로() {
        List<Judged> judged = List.of(
                judge(101, 1, "Y3_4", "NONE", LocalDate.of(2026, 7, 18)), // 지원 가능 · 기준일 전에 마감
                judge(102, 2, "Y3_4", "NONE", null),                      // 지원 가능 · 적합도 2위
                judge(103, 3, "Y4", "NONE", null),                        // 지원 불가
                judge(104, 4, "Y3_4", "REQUIRED", null),                  // 확인 필요(포트폴리오)
                judge(105, 5, "Y3_4", "NONE", null),                      // 지원 가능 · 적합도 1위
                judge(106, 6, "Y3_4", "NONE", LocalDate.of(2026, 7, 24)), // 지원 가능 · 기준일 다음 날 마감(아직 모집 중)
                judge(107, 7, "Y4", "NONE", LocalDate.of(2026, 7, 18)));  // 지원 불가 · 마감
        // 적합도 순위(지원 불가 제외): 105 → 104 → 106 → 102 → 101
        List<Integer> fit = List.of(105, 104, 106, 102, 101);

        assertThat(EligibilityService.order(judged, fit, new AsOf(AS_OF, END))).extracting(j -> j.requirement().jobId())
                .containsExactly(105, 106, 102, 101, 104, 103, 107);
    }

    @Test
    void 적합도_순위가_없으면_리스트_순번() {
        List<Judged> judged = List.of(judge(102, 2, "Y3_4", "NONE", null), judge(101, 1, "Y3_4", "NONE", null));
        assertThat(EligibilityService.order(judged, List.of(), new AsOf(AS_OF, END))).extracting(j -> j.requirement().jobId())
                .containsExactly(101, 102);
    }

    @Test
    void 마감은_마감일_당일부터() {
        assertThat(EligibilityService.closedOn(judge(1, 1, "Y3_4", "NONE", AS_OF), AS_OF)).isTrue();
        assertThat(EligibilityService.closedOn(judge(1, 1, "Y3_4", "NONE", AS_OF.plusDays(1)), AS_OF)).isFalse();
        assertThat(EligibilityService.closedOn(judge(1, 1, "Y3_4", "NONE", null), AS_OF)).isFalse();
    }

    @Test
    void 마감은_한_규칙이다_마감일_당일부터이거나_모집이_끝난_뒤() {
        AsOf inPeriod = new AsOf(AS_OF, END);
        assertThat(inPeriod.closed(AS_OF)).isTrue();
        assertThat(inPeriod.closed(AS_OF.plusDays(1))).isFalse();
        assertThat(inPeriod.closed(null)).isFalse();
        // 모집 종료일 뒤면 마감일이 없거나 늦어도 마감(ADR-0035)
        AsOf after = new AsOf(END.plusDays(1), END);
        assertThat(after.closed(null)).isTrue();
        assertThat(after.closed(END.plusDays(30))).isTrue();
        assertThat(new AsOf(END, END).closed(null)).isFalse();
    }

    private static Judged judge(int id, int listSeq, String gradeRule, String portfolio, LocalDate closesOn) {
        JobRequirement r = new JobRequirement(id, listSeq, "(가상)직무" + id, "(가상)팀", new InstitutionRef(1, "(가상)기관"),
                "SEMESTER", gradeRule, null, portfolio, "NONE", null, null, Map.of(), "미용예술대학", false, Set.of(43),
                List.of(), new Closing(closesOn, closesOn == null ? null : "CENTER_CLOSED", false), 0);
        return new Judged(r, EligibilityRules.judge(r, ME, "메이크업디자인학과"));
    }
}
