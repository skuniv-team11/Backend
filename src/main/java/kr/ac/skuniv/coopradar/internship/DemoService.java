package kr.ac.skuniv.coopradar.internship;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.ac.skuniv.coopradar.auth.AuthUser;
import kr.ac.skuniv.coopradar.common.ApiException;
import kr.ac.skuniv.coopradar.common.ErrorCode;
import kr.ac.skuniv.coopradar.common.Times;
import kr.ac.skuniv.coopradar.eligibility.EligibilityDtos.Verdict;
import kr.ac.skuniv.coopradar.eligibility.EligibilityService;
import kr.ac.skuniv.coopradar.eligibility.ProfileInput;
import kr.ac.skuniv.coopradar.internship.ApplicationRepository.Demo;
import kr.ac.skuniv.coopradar.internship.ApplicationRepository.PickRow;
import kr.ac.skuniv.coopradar.internship.ApplicationRepository.Row;
import kr.ac.skuniv.coopradar.internship.ApplicationRepository.Scope;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.Advanced;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.Applicant;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.ApprovalKind;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.DemoStep;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.GuestStage;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.InterviewMode;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.Result;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.Resume;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.Status;
import kr.ac.skuniv.coopradar.me.ProfileRepository;
import kr.ac.skuniv.coopradar.me.SavedProfile;
import kr.ac.skuniv.coopradar.reference.ReferenceDtos.CurrentRound;
import kr.ac.skuniv.coopradar.reference.RoundService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 체험 묶음(ADR-0033). 같은 브라우저에서 만든 체험 학생·센터를 한 묶음으로 잇고, 묶음마다 가상 지원자 6명을 따로 만든다
 * (심사위원끼리 섞이지 않게). 체험 학생은 기준일(지원 중·실습 중·마친 뒤)과, 실습 중·마친 뒤면 지난 기록(지원서·매칭·합격)을
 * 갖고 시작한다. 지난 기록과 가상 지원자는 모두 is_virtual — 응답에 virtual로 나간다.
 * 값은 resources/demo/demo-applicants.json(v2 프로토타입과 같은 값).
 */
@Service
public class DemoService {

    private static final Logger log = LoggerFactory.getLogger(DemoService.class);
    static final Template TEMPLATE = load();

    private final ApplicationRepository repo;
    private final ProfileRepository profiles;
    private final EligibilityService eligibility;
    private final RoundService rounds;
    private final InternshipProperties props;
    private final Clock clock;

    public DemoService(ApplicationRepository repo, ProfileRepository profiles, EligibilityService eligibility,
                       RoundService rounds, InternshipProperties props, Clock clock) {
        this.repo = repo;
        this.profiles = profiles;
        this.eligibility = eligibility;
        this.rounds = rounds;
        this.props = props;
        this.clock = clock;
    }

    // ───────────── 템플릿 ─────────────

    record Close(boolean report, boolean credit, boolean survey, Boolean evaluation, Boolean attendance,
                 boolean approved) {
    }

    record Virtual(String receiptNo, LocalDate submittedOn, String name, String department, int grade, int semesters,
                   BigDecimal gpa, Boolean graduationExpected, int counsel, Status status, List<Integer> picks,
                   OffsetDateTime interview, InterviewMode mode, Result result, String fixReason, Close close) {
    }

    record Student(String receiptNo, LocalDate submittedOn, String name, String nameEn, LocalDate birthDate,
                   String gender, String phone, String address, String studentNo, List<Integer> picks,
                   Integer candidate, OffsetDateTime interview, InterviewMode mode, Result result, List<String> essays,
                   Resume resume, Close close) {
    }

    record Template(List<Virtual> applicants, Student student) {
    }

    private static Template load() {
        try {
            return ApplicationRepository.JSON.readValue(
                    new ClassPathResource("demo/demo-applicants.json").getContentAsString(java.nio.charset.StandardCharsets.UTF_8),
                    Template.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static final Instant MATCHED_AT = at(LocalDate.of(2026, 7, 28), 17);
    static final Instant NOTIFIED_AT = at(LocalDate.of(2026, 8, 5), 17);

    private static Instant at(LocalDate day, int hour) {
        return day.atTime(LocalTime.of(hour, 0)).atZone(Times.KST).toInstant();
    }

    // ───────────── 체험 계정 ─────────────

    LocalDate todayOf(GuestStage stage) {
        return switch (stage) {
            case APPLYING -> props.applyingToday();
            case PRACTICING -> props.practicingToday();
            case DONE -> props.doneToday();
        };
    }

    /**
     * 체험 계정을 묶음에 넣는다(AuthService.guest가 계정과 예시 프로필을 만든 뒤 부른다). 묶음이 없거나 만료됐으면 새로 만들고
     * 가상 지원자를 넣는다. 학생은 기준일과, 실습 중·마친 뒤면 지난 기록을 넣는다. 센터는 기준일이 없다.
     */
    @Transactional
    public InternshipDtos.Demo join(long userId, boolean student, GuestStage stage, UUID requested, Instant expiresAt) {
        UUID group = requested != null ? requested : UUID.randomUUID();
        if (repo.upsertGroup(group, expiresAt)) {
            seedVirtualApplicants(group);
        }
        GuestStage s = stage == null ? GuestStage.APPLYING : stage;
        LocalDate today = student ? todayOf(s) : null;
        repo.setUserDemo(userId, group, today);
        if (student && s != GuestStage.APPLYING) {
            seedStudentHistory(userId, group, s);
        }
        return new InternshipDtos.Demo(group, today);
    }

    private void seedVirtualApplicants(UUID group) {
        CurrentRound round = rounds.current();
        for (Virtual v : TEMPLATE.applicants()) {
            Integer dep = repo.departmentId(v.department()).orElse(null);
            if (dep == null) {
                log.warn("가상 지원자 학과가 시드에 없어 넣지 않습니다: {}", v.department());
                continue;
            }
            boolean grad = Boolean.TRUE.equals(v.graduationExpected());
            Applicant a = new Applicant(v.name(), "Virtual Applicant " + v.receiptNo().substring(v.receiptNo().length() - 1),
                    LocalDate.of(2003, 1, 1), null, "010-0000-0000", null, "서울특별시 성북구 (가상)",
                    "20230000" + v.receiptNo().substring(v.receiptNo().length() - 2), null);
            Instant submitted = at(v.submittedOn(), 10);
            Instant received = v.status() == Status.RECEIVED ? at(v.submittedOn().plusDays(1), 10) : null;
            long id = repo.insertDemo(round.id(), null, group, v.status(), v.receiptNo(), a, dep, v.grade(), v.semesters(),
                    v.gpa(), grad, Resume.EMPTY, VIRTUAL_ESSAYS, v.name(), v.counsel(), v.fixReason(), submitted, received);
            repo.replacePicks(id, verdictPicks(round, v.picks(), new ProfileInput(dep, v.grade(), v.semesters(), v.gpa(),
                    grad, null, null, null)));
            approveDemo(id, v.picks(), submitted);
        }
    }

    static final List<String> VIRTUAL_ESSAYS = List.of(
            "(가상 지원서) 시연용 가상 지원자라 자기소개서 내용은 비워 두었어요.",
            "(가상 지원서) 시연용 가상 지원자라 자기소개서 내용은 비워 두었어요.",
            "(가상 지원서) 시연용 가상 지원자라 자기소개서 내용은 비워 두었어요.",
            "(가상 지원서) 시연용 가상 지원자라 자기소개서 내용은 비워 두었어요.");

    private void seedStudentHistory(long userId, UUID group, GuestStage stage) {
        CurrentRound round = rounds.current();
        Student t = TEMPLATE.student();
        SavedProfile p = profiles.findSaved(userId, true).orElse(null);
        if (p == null) {
            log.warn("예시 프로필이 없어 체험 학생 지난 기록을 넣지 않습니다");
            return;
        }
        Instant added = at(LocalDate.of(2026, 7, 20), 20);
        for (int i = 0; i < t.picks().size(); i++) {
            repo.insertPlanItem(userId, t.picks().get(i), i + 1, added.plusSeconds(i));
        }
        if (t.candidate() != null) {
            repo.insertPlanItem(userId, t.candidate(), null, added.plusSeconds(10));
        }
        Applicant a = new Applicant(t.name(), t.nameEn(), t.birthDate(), t.gender(), t.phone(), null, t.address(),
                t.studentNo(), null);
        Instant submitted = at(t.submittedOn(), 14);
        String receipt = repo.nextReceiptNo(round.id(), group, round.termCode());
        long id = repo.insertDemo(round.id(), userId, group, Status.SUBMITTED, receipt, a, p.departmentId(), p.grade(),
                p.completedSemesters(), p.gpa(), p.graduationExpected(), t.resume(), t.essays(), t.name(), 0, null,
                submitted, null);
        repo.replacePicks(id, verdictPicks(round, t.picks(), new ProfileInput(p.departmentId(), p.grade(),
                p.completedSemesters(), p.gpa(), p.graduationExpected(), p.interestText(), p.homeAreaCode(),
                p.certificates())));
        approveDemo(id, t.picks(), submitted.minusSeconds(3600));
        repo.receiveAt(id, at(t.submittedOn().plusDays(1), 10));
        repo.setPlacementDirect(id, 1, MATCHED_AT, t.interview(), t.mode(), t.result(), NOTIFIED_AT);
        if (stage == GuestStage.DONE) {
            closeDemo(id, t.close(), at(LocalDate.of(2026, 12, 16), 10));
        }
    }

    private List<PickRow> verdictPicks(CurrentRound round, List<Integer> jobIds, ProfileInput profile) {
        Map<Integer, Verdict> verdicts = new HashMap<>();
        eligibility.judgeAll(round.id(), profile).forEach(j -> verdicts.put(j.requirement().jobId(), j.result().verdict()));
        List<PickRow> out = new ArrayList<>();
        for (int i = 0; i < jobIds.size(); i++) {
            out.add(new PickRow(i + 1, jobIds.get(i), verdicts.getOrDefault(jobIds.get(i), Verdict.NEEDS_CHECK)));
        }
        return out;
    }

    private void approveDemo(long id, List<Integer> pickIds, Instant at) {
        Row row = repo.byId(id).orElseThrow();
        repo.insertApproved(id, ApprovalKind.APPLICATION, ApplicationViews.token(),
                ApplicationViews.contentHash(row, pickIds), at.minusSeconds(3600), at.minusSeconds(600));
    }

    private void closeDemo(long id, Close c, Instant at) {
        if (c.report()) {
            repo.setClose(id, "report_submitted_at", at.minusSeconds(86400 * 2));
        }
        if (c.credit()) {
            repo.setClose(id, "credit_submitted_at", at.minusSeconds(86400));
        }
        if (c.survey()) {
            repo.setClose(id, "survey_submitted_at", at.minusSeconds(86400));
        }
        if (!Boolean.FALSE.equals(c.evaluation())) {
            repo.setClose(id, "evaluation_received_at", at);
        }
        if (!Boolean.FALSE.equals(c.attendance())) {
            repo.setClose(id, "attendance_received_at", at);
        }
        repo.insertApproved(id, ApprovalKind.CREDIT, ApplicationViews.token(), null, at,
                c.approved() ? at.plusSeconds(3600) : null);
    }

    // ───────────── 시연 버튼(#58) ─────────────

    /**
     * 체험 센터 계정의 묶음을 그 단계까지 한 번에 진행한다(앞 단계도 함께). 가입 계정은 403 DEMO_ONLY.
     * RECEIVED 낸 지원서 접수 · MATCHED 지망 고르지 않은 지원자를 1지망으로 매칭하고 확정 · SELECTED 면접 일정·결과를 넣고 알림 ·
     * CLOSING 기관 서류(평가표·출근부)를 받고, 가상 지원자는 학생 서류·학과(부)장 승인까지. 보완 요청 중인 지원서는 그대로 둔다.
     */
    @Transactional
    public Advanced advance(AuthUser user, DemoStep to) {
        Demo demo = repo.demo(user.id());
        if (!demo.guest() || demo.group() == null) {
            throw new ApiException(ErrorCode.DEMO_ONLY, "시연 버튼은 체험 센터 계정에서만 써요");
        }
        Scope scope = Scope.of(demo);
        int roundId = rounds.current().id();
        Instant now = clock.instant();
        int changed = 0;
        Map<String, Virtual> byReceipt = new HashMap<>();
        TEMPLATE.applicants().forEach(v -> byReceipt.put(v.receiptNo(), v));
        for (Row r : repo.inScope(scope, roundId)) {
            if (r.status() == Status.SUBMITTED) {
                repo.receive(r.id(), now);
                changed++;
            }
        }
        if (to.compareTo(DemoStep.MATCHED) >= 0) {
            for (Row r : repo.inScope(scope, roundId)) {
                if (r.status() == Status.RECEIVED && r.matchedRank() == null) {
                    repo.setMatchedRank(r.id(), 1, now);
                    changed++;
                }
            }
            changed += repo.confirmMatches(scope, roundId, now);
        }
        if (to.compareTo(DemoStep.SELECTED) >= 0) {
            Student s = TEMPLATE.student();
            for (Row r : repo.inScope(scope, roundId)) {
                if (r.status() == Status.MATCHED && r.notifiedAt() == null) {
                    Virtual v = r.virtual() && r.userId() == null ? byReceipt.get(r.receiptNo()) : null;
                    boolean preset = v != null && v.result() != null;
                    repo.setSelection(r.id(), preset ? v.interview() : s.interview(), preset ? v.mode() : s.mode(),
                            preset ? v.result() : Result.PASS, now);
                    changed++;
                }
            }
            changed += repo.notifyResults(scope, roundId, now);
        }
        if (to == DemoStep.CLOSING) {
            List<Row> passed = repo.inScope(scope, roundId).stream().filter(TimelineService::passed).toList();
            var closes = repo.closes(passed.stream().map(Row::id).toList());
            var credits = repo.approvals(passed.stream().map(Row::id).toList(), ApprovalKind.CREDIT);
            for (Row r : passed) {
                if (credits.containsKey(r.id())) {
                    continue;
                }
                Virtual v = r.virtual() && r.userId() == null ? byReceipt.get(r.receiptNo()) : null;
                if (v != null && v.close() != null) {
                    closeDemo(r.id(), v.close(), now);
                } else if (!closes.containsKey(r.id()) || closes.get(r.id()).evaluation() == null) {
                    repo.setClose(r.id(), "evaluation_received_at", now);
                    repo.setClose(r.id(), "attendance_received_at", now);
                    repo.requestApproval(r.id(), ApprovalKind.CREDIT, ApplicationViews.token(), null, now);
                }
                changed++;
            }
        }
        return new Advanced(to, changed);
    }
}
