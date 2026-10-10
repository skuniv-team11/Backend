package kr.ac.skuniv.coopradar.internship;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kr.ac.skuniv.coopradar.auth.AuthUser;
import kr.ac.skuniv.coopradar.common.ApiException;
import kr.ac.skuniv.coopradar.common.ErrorCode;
import kr.ac.skuniv.coopradar.internship.ApplicationRepository.ApprovalRow;
import kr.ac.skuniv.coopradar.internship.ApplicationRepository.CloseRow;
import kr.ac.skuniv.coopradar.internship.ApplicationRepository.Demo;
import kr.ac.skuniv.coopradar.internship.ApplicationRepository.JobInfo;
import kr.ac.skuniv.coopradar.internship.ApplicationRepository.PickRow;
import kr.ac.skuniv.coopradar.internship.ApplicationRepository.Row;
import kr.ac.skuniv.coopradar.internship.ApplicationRepository.WeekPlanRow;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.Approval;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.ApprovalKind;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.ApplicationBrief;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.Documents;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.Internship;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.Interview;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.NcsRef;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.Next;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.NextKind;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.Placement;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.PlanPick;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.Practice;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.Result;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.StageState;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.Status;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.StudentDocument;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.TimelineStage;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.WeekPlan;
import kr.ac.skuniv.coopradar.job.RoundRef;
import kr.ac.skuniv.coopradar.reference.ReferenceDtos.Stage;
import kr.ac.skuniv.coopradar.reference.ReferenceDtos.StageInfo;
import kr.ac.skuniv.coopradar.reference.ReferenceDates;
import kr.ac.skuniv.coopradar.reference.ReferenceDates.AsOf;
import kr.ac.skuniv.coopradar.reference.RoundService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 내 현장실습(#44·#45, ADR-0033): 회차 일정 11단계를 기준일로 나누고(완료·지금·앞으로), 그 위에 내 지원서·매칭·선발·실습·
 * 마무리 서류를 얹는다. 기준일은 asOf → 체험 학생의 기준일 → 오늘(한국 시간).
 * 선발 결과는 센터가 학생에게 알린 뒤에만 보인다. 실습 주차는 직무의 실습 기간으로 센다.
 */
@Service
public class TimelineService {

    private final ApplicationRepository repo;
    private final ApplicationService applications;
    private final RoundService rounds;
    private final InternshipProperties props;
    private final ReferenceDates dates;
    private final Clock clock;

    public TimelineService(ApplicationRepository repo, ApplicationService applications, RoundService rounds,
                           InternshipProperties props, ReferenceDates dates, Clock clock) {
        this.repo = repo;
        this.applications = applications;
        this.rounds = rounds;
        this.props = props;
        this.dates = dates;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Internship timeline(AuthUser user, LocalDate requestedAsOf) {
        var round = rounds.current();
        Demo demo = repo.demo(user.id());
        LocalDate asOf = dates.progress(requestedAsOf, demo.today());
        AsOf closing = ReferenceDates.on(asOf, round);
        Stages stages = stages(round.stages(), asOf);
        List<PlanPick> picks = repo.plan(user.id()).stream()
                .map(p -> new PlanPick(p.rank(), p.jobId(), p.title(), p.institution(),
                        p.ncsCode() == null ? null : new NcsRef(p.ncsCode(), p.ncsName()),
                        closing.closed(p.closesOn())))
                .toList();
        Row row = repo.byUser(user.id(), round.id()).orElse(null);
        ApplicationBrief brief = null;
        Placement placement = null;
        Documents documents = null;
        if (row != null) {
            ApprovalRow a = repo.approval(row.id(), ApprovalKind.APPLICATION).orElse(null);
            Approval approval = a == null ? Approval.NONE
                    : new Approval(applications.approvalStatus(row), a.token(), a.requestedAt(), a.approvedAt());
            brief = new ApplicationBrief(row.id(), row.status(), row.receiptNo(), row.submittedAt(), approval,
                    row.fixReason(), row.counselCount(), row.virtual());
            Optional<JobInfo> job = placedJob(row);
            if (job.isPresent()) {
                placement = placement(row, job.get(), asOf);
                if (passed(row)) {
                    documents = documents(row, user.id(), job.get().id());
                }
            }
        }
        return new Internship(asOf, new RoundRef(round.id(), round.termCode()), stages.list(), stages.now(),
                stages.next(), picks, brief, placement, documents);
    }

    /** #45 마무리 서류 냄 표시. 선발(합격 알림) 뒤에만, 수행결과보고서는 그 자리의 커리어 리포트가 있어야 한다. */
    @Transactional
    public Internship submitDocument(AuthUser user, StudentDocument kind) {
        Row row = repo.byUser(user.id(), rounds.current().id())
                .orElseThrow(() -> new ApiException(ErrorCode.STATE_CONFLICT, "선발된 뒤에 낼 수 있어요"));
        JobInfo job = placedJob(row).filter(j -> passed(row))
                .orElseThrow(() -> new ApiException(ErrorCode.STATE_CONFLICT, "선발된 뒤에 낼 수 있어요"));
        if (kind == StudentDocument.REPORT && repo.careerReport(user.id(), job.id()).isEmpty()) {
            throw new ApiException(ErrorCode.CAREER_REPORT_REQUIRED,
                    "수행결과보고서의 '실습 내용'으로 이 자리의 커리어 리포트를 먼저 만들어 주세요");
        }
        String column = switch (kind) {
            case REPORT -> "report_submitted_at";
            case CREDIT -> "credit_submitted_at";
            case SURVEY -> "survey_submitted_at";
        };
        repo.setClose(row.id(), column, clock.instant());
        return timeline(user, null);
    }

    static boolean passed(Row r) {
        return r.status() == Status.MATCHED && r.notifiedAt() != null && r.result() == Result.PASS;
    }

    /** 매칭 확정된 자리(낸 지망 중 matched_rank). */
    private Optional<JobInfo> placedJob(Row r) {
        if (r.status() != Status.MATCHED || r.matchedRank() == null) {
            return Optional.empty();
        }
        return repo.picks(List.of(r.id())).getOrDefault(r.id(), List.of()).stream()
                .filter(p -> p.rank() == r.matchedRank()).findFirst()
                .map(PickRow::jobId).map(id -> repo.jobs(List.of(id)).get(id));
    }

    private Placement placement(Row r, JobInfo j, LocalDate asOf) {
        Interview interview = r.interviewAt() == null ? null : new Interview(r.interviewAt(), r.interviewMode());
        Result result = r.notifiedAt() == null ? null : r.result();
        Practice practice = result == Result.PASS ? practice(j, asOf, repo.weeklyPlans(j.id())) : null;
        return new Placement(j.id(), j.title(), j.team(), j.institution(), r.matchedRank(), r.matchedAt(), interview,
                result, r.notifiedAt(), practice);
    }

    private Documents documents(Row r, long userId, int jobId) {
        CloseRow c = repo.closes(List.of(r.id())).getOrDefault(r.id(), CloseRow.empty(r.id()));
        ApprovalRow credit = repo.approval(r.id(), ApprovalKind.CREDIT).orElse(null);
        return new Documents(c.report(), c.credit(), c.survey(), c.evaluation(), c.attendance(),
                repo.careerReport(userId, jobId).orElse(null), ApplicationViews.approval(credit, null, false),
                c.remindedAt());
    }

    Practice practice(JobInfo j, LocalDate asOf, List<WeekPlanRow> plans) {
        if (j.periodStart() == null || j.periodEnd() == null) {
            return null;
        }
        int days = (int) ChronoUnit.DAYS.between(j.periodStart(), j.periodEnd()) + 1;
        int day = asOf.isBefore(j.periodStart()) ? 0
                : (int) Math.min(days, ChronoUnit.DAYS.between(j.periodStart(), asOf) + 1);
        int weeks = (days + 6) / 7;
        int week = day == 0 ? 0 : (day - 1) / 7 + 1;
        WeekPlan current = null;
        WeekPlan next = null;
        int at = -1;
        for (int i = 0; i < plans.size() && week > 0; i++) {
            if (weeksOf(plans.get(i).weeks(), weeks).contains(week)) {
                at = i;
                break;
            }
        }
        if (at >= 0) {
            current = plan(plans.get(at));
            next = at + 1 < plans.size() ? plan(plans.get(at + 1)) : null;
        } else if (week == 0 && !plans.isEmpty()) {
            next = plan(plans.get(0));
        }
        return new Practice(j.periodStart(), j.periodEnd(), day, days, week, weeks, j.weekdays(), j.workHours(),
                j.weeklyHours(), j.stipend(), props.course(), props.credits(), current, next);
    }

    private static WeekPlan plan(WeekPlanRow r) {
        return new WeekPlan(r.weeks(), r.content());
    }

    private static final Pattern RANGE = Pattern.compile("(\\d+)\\s*[~\\-]\\s*(\\d+)");
    private static final Pattern OPEN_END = Pattern.compile("(\\d+)\\s*주차\\s*~");
    private static final Pattern NUMBER = Pattern.compile("\\d+");

    /** 주차 계획 칸('7~8주차' · '3-5주차' · '1,3주차' · '10주차~' · '모든 주차')이 가리키는 주. 못 읽으면 빈 집합. */
    static Set<Integer> weeksOf(String label, int maxWeeks) {
        Set<Integer> out = new LinkedHashSet<>();
        if (label == null || !label.contains("주")) {
            return out;
        }
        if (label.contains("모든")) {
            for (int w = 1; w <= maxWeeks; w++) {
                out.add(w);
            }
            return out;
        }
        Matcher m = RANGE.matcher(label);
        boolean any = false;
        while (m.find()) {
            any = true;
            for (int w = Integer.parseInt(m.group(1)); w <= Integer.parseInt(m.group(2)); w++) {
                out.add(w);
            }
        }
        if (any) {
            return out;
        }
        Matcher open = OPEN_END.matcher(label);
        if (open.find()) {
            for (int w = Integer.parseInt(open.group(1)); w <= maxWeeks; w++) {
                out.add(w);
            }
            return out;
        }
        Matcher n = NUMBER.matcher(label);
        while (n.find()) {
            out.add(Integer.parseInt(n.group()));
        }
        return out;
    }

    // ───────────── 일정 단계 ─────────────

    record Stages(List<TimelineStage> list, Stage now, Next next) {
    }

    /**
     * 단계 나누기. 지난 단계: 끝일이 기준일 전(끝일이 없으면 뒤 단계가 시작됨). 진행 중: 시작했고 안 지남.
     * 지금(now) = 진행 중인 단계 중 가장 뒤, 없으면 아직 안 지난 첫 단계(다음에 올 것). 지금보다 앞은 완료.
     * next = 지금 단계의 끝일과 뒤 단계의 시작일 중 기준일 이후 가장 가까운 것.
     */
    static Stages stages(List<StageInfo> stages, LocalDate asOf) {
        int n = stages.size();
        boolean[] past = new boolean[n];
        boolean[] active = new boolean[n];
        for (int i = 0; i < n; i++) {
            StageInfo s = stages.get(i);
            if (s.endsOn() != null) {
                past[i] = s.endsOn().isBefore(asOf);
            } else {
                for (int k = i + 1; k < n; k++) {
                    LocalDate start = stages.get(k).startsOn();
                    if (start != null && !start.isAfter(asOf)) {
                        past[i] = true;
                        break;
                    }
                }
            }
            active[i] = s.startsOn() != null && !s.startsOn().isAfter(asOf) && !past[i];
        }
        int now = -1;
        for (int i = n - 1; i >= 0 && now < 0; i--) {
            if (active[i]) {
                now = i;
            }
        }
        for (int i = 0; i < n && now < 0; i++) {
            if (!past[i]) {
                now = i;
            }
        }
        List<TimelineStage> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            StageInfo s = stages.get(i);
            StageState state = past[i] || i < now ? StageState.DONE : i == now ? StageState.NOW : StageState.NEXT;
            list.add(new TimelineStage(s.code(), s.phase(), s.startsOn(), s.endsOn(), s.confirmed(), state));
        }
        Next next = null;
        if (now >= 0) {
            StageInfo cur = stages.get(now);
            if (active[now] && cur.endsOn() != null) {
                next = new Next(cur.code(), NextKind.END, cur.endsOn(), days(asOf, cur.endsOn()));
            } else if (!active[now] && cur.startsOn() != null) {
                next = new Next(cur.code(), NextKind.START, cur.startsOn(), days(asOf, cur.startsOn()));
            }
            for (int k = now + 1; k < n; k++) {
                LocalDate start = stages.get(k).startsOn();
                if (start != null && start.isAfter(asOf) && (next == null || start.isBefore(next.on()))) {
                    next = new Next(stages.get(k).code(), NextKind.START, start, days(asOf, start));
                }
            }
        }
        return new Stages(list, now < 0 ? null : stages.get(now).code(), next);
    }

    private static int days(LocalDate from, LocalDate to) {
        return (int) ChronoUnit.DAYS.between(from, to);
    }
}
