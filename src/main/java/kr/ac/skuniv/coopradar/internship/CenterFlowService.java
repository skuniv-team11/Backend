package kr.ac.skuniv.coopradar.internship;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import kr.ac.skuniv.coopradar.auth.AuthUser;
import kr.ac.skuniv.coopradar.common.ApiException;
import kr.ac.skuniv.coopradar.common.ErrorCode;
import kr.ac.skuniv.coopradar.common.Times;
import kr.ac.skuniv.coopradar.internship.ApplicationRepository.ApprovalRow;
import kr.ac.skuniv.coopradar.internship.ApplicationRepository.CloseRow;
import kr.ac.skuniv.coopradar.internship.ApplicationRepository.JobInfo;
import kr.ac.skuniv.coopradar.internship.ApplicationRepository.PickRow;
import kr.ac.skuniv.coopradar.internship.ApplicationRepository.Row;
import kr.ac.skuniv.coopradar.internship.ApplicationRepository.Scope;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.Application;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.ApprovalKind;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.CloseBoard;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.CloseCounts;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.CloseDocuments;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.CloseItem;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.Consents;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.Inbox;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.InboxCounts;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.InboxRow;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.Interview;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.InterviewMode;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.JobBrief;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.JobLoad;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.Pick;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.PlacementBoard;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.PlacementCounts;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.PlacementRow;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.Reminded;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.Result;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.Status;
import kr.ac.skuniv.coopradar.job.RoundRef;
import kr.ac.skuniv.coopradar.reference.ReferenceDtos.CurrentRound;
import kr.ac.skuniv.coopradar.reference.ReferenceDates;
import kr.ac.skuniv.coopradar.reference.RoundService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 센터 쪽 진행(#46~#57, ADR-0033). 기관 계정이 없어 기관 값(면접 일정·결과·평가표·출근부)은 센터가 넣는다.
 * 범위: 체험 센터는 자기 묶음, 가입 센터는 묶음 없는 지원서. 작성 중(DRAFT)은 보이지 않는다.
 * <ul>
 *   <li>접수함: 새로 들어옴(SUBMITTED) → 접수 완료(RECEIVED) 또는 보완 요청(FIX_REQUESTED, 이유 필수). 매칭 확정 뒤에는 못 바꾼다</li>
 *   <li>매칭: 접수 완료 지원서마다 1~3지망 중 하나를 고르고(순위를 매기지 않는다), 모두 고르면 확정 → 학생에게 보인다</li>
 *   <li>선발: 매칭된 학생의 면접 일정·결과(대기·합격·불합격). 대기가 없으면 알림 → 학생에게 결과가 보인다</li>
 *   <li>마무리: 합격 알림을 받은 학생의 학생 서류·기관 서류·학과(부)장 승인(평가표·출근부를 모두 받으면 승인 링크가 생긴다)</li>
 * </ul>
 */
@Service
public class CenterFlowService {

    static final int REASON_MIN = 5;
    static final int REASON_MAX = 300;

    private final ApplicationRepository repo;
    private final ApplicationService applications;
    private final RoundService rounds;
    private final InternshipProperties props;
    private final ReferenceDates dates;
    private final Clock clock;

    public CenterFlowService(ApplicationRepository repo, ApplicationService applications, RoundService rounds,
                             InternshipProperties props, ReferenceDates dates, Clock clock) {
        this.repo = repo;
        this.applications = applications;
        this.rounds = rounds;
        this.props = props;
        this.dates = dates;
        this.clock = clock;
    }

    private Scope scope(AuthUser user) {
        return Scope.of(repo.demo(user.id()));
    }

    private Row inScope(AuthUser user, long applicationId) {
        return inScope(user, applicationId, false);
    }

    /** @param lock 바꾸는 요청이면 행을 잠근다(학생의 저장·다시 내기와 겹치지 않게) */
    private Row inScope(AuthUser user, long applicationId, boolean lock) {
        Scope scope = scope(user);
        return (lock ? repo.byIdForUpdate(applicationId) : repo.byId(applicationId))
                .filter(r -> r.status() != Status.DRAFT && r.roundId() == rounds.current().id())
                .filter(r -> scope.demo() ? Objects.equals(r.group(), scope.group()) && scope.group() != null
                        : r.group() == null)
                .orElseThrow(() -> new ApiException(ErrorCode.APPLICATION_NOT_FOUND, "없는 지원서예요"));
    }

    private record Loaded(List<Row> rows, Map<Long, List<PickRow>> picks, Map<Integer, JobInfo> jobs) {

        List<PickRow> picksOf(Row r) {
            return picks.getOrDefault(r.id(), List.of());
        }

        JobInfo matchedJob(Row r) {
            if (r.matchedRank() == null) {
                return null;
            }
            return picksOf(r).stream().filter(p -> p.rank() == r.matchedRank()).findFirst()
                    .map(p -> jobs.get(p.jobId())).orElse(null);
        }
    }

    private Loaded load(List<Row> rows) {
        Map<Long, List<PickRow>> picks = repo.picks(rows.stream().map(Row::id).toList());
        List<Integer> jobIds = picks.values().stream().flatMap(List::stream).map(PickRow::jobId).distinct().toList();
        return new Loaded(rows, picks, repo.jobs(jobIds));
    }

    // ───────────── 접수함 ─────────────

    @Transactional(readOnly = true)
    public Inbox inbox(AuthUser user) {
        Loaded l = load(repo.inScope(scope(user), rounds.current().id()));
        List<InboxRow> items = l.rows().stream()
                .map(r -> new InboxRow(r.id(), r.receiptNo(), r.submittedAt(), r.status(), r.virtual(),
                        ApplicationViews.student(r), r.counselCount(),
                        ApplicationViews.centerPicks(l.picksOf(r), l.jobs())))
                .toList();
        return new Inbox(new InboxCounts(items.size(), count(items, Status.SUBMITTED), count(items, Status.RECEIVED),
                count(items, Status.FIX_REQUESTED), count(items, Status.MATCHED)), items);
    }

    private static int count(List<InboxRow> rows, Status s) {
        return (int) rows.stream().filter(r -> r.status() == s).count();
    }

    @Transactional(readOnly = true)
    public Application detail(AuthUser user, long applicationId) {
        return view(inScope(user, applicationId));
    }

    /**
     * 접수(RECEIVED)는 새로 들어온(SUBMITTED) 지원서만 — 보완 요청 중인 지원서는 학생이 다시 내야 접수한다(이미 접수 완료면 그대로).
     * 보완 요청은 낸 지원서(새로 들어옴·접수 완료·보완 요청 중)에 매칭 확정 전까지.
     */
    @Transactional
    public Application setStatus(AuthUser user, long applicationId, Status target, String reason) {
        Row r = inScope(user, applicationId, true);
        if (r.status() == Status.MATCHED) {
            throw new ApiException(ErrorCode.STATE_CONFLICT, "매칭이 확정된 지원서는 상태를 바꿀 수 없어요");
        }
        if (target == Status.RECEIVED) {
            if (r.status() == Status.FIX_REQUESTED) {
                throw new ApiException(ErrorCode.STATE_CONFLICT, "보완 요청 중인 지원서는 학생이 고쳐서 다시 내면 접수할 수 있어요");
            }
            if (r.status() == Status.SUBMITTED && repo.receive(r.id(), clock.instant()) != 1) {
                throw new ApiException(ErrorCode.STATE_CONFLICT, "지원서 상태가 바뀌었어요. 다시 불러 주세요");
            }
        } else if (target == Status.FIX_REQUESTED) {
            String why = ApplicationService.blankToNull(reason);
            if (why == null || why.length() < REASON_MIN || why.length() > REASON_MAX) {
                throw ApiException.invalid("reason", "보완 요청 이유를 5~300자로 적어 주세요");
            }
            if (repo.requestFix(r.id(), why, clock.instant()) != 1) {
                throw new ApiException(ErrorCode.STATE_CONFLICT, "지원서 상태가 바뀌었어요. 다시 불러 주세요");
            }
        } else {
            throw ApiException.invalid("status", "RECEIVED 또는 FIX_REQUESTED");
        }
        return view(repo.byId(r.id()).orElseThrow());
    }

    /**
     * 센터가 보는 지원서 한 부(제5호 서식 보기). 승인 링크 값은 주지 않는다. 지망은 낸 그대로(마감 여부는 낸 날 기준),
     * 승인 상태는 학생 화면과 같은 값이다(보완 요청 중이면 학생이 고치는 지금 내용으로 본다).
     */
    private Application view(Row r) {
        CurrentRound round = rounds.current();
        LocalDate asOf = dates.today();
        List<PickRow> pickRows = repo.picks(List.of(r.id())).getOrDefault(r.id(), List.of());
        List<Integer> pickIds = pickRows.stream().map(PickRow::jobId).toList();
        List<Pick> picks = ApplicationViews.snapshotPicks(pickRows, repo.jobs(pickIds),
                ReferenceDates.on(ApplicationViews.submittedOn(r, asOf), round));
        ApprovalRow approvalRow = repo.approval(r.id(), ApprovalKind.APPLICATION).orElse(null);
        var approval = ApplicationViews.approval(approvalRow, ApplicationViews.contentHash(r, applications.basis(r)), false);
        var academic = ApplicationViews.academic(r);
        return new Application(r.id(), r.status(), r.receiptNo(), r.virtual(), new RoundRef(round.id(), round.termCode()),
                asOf, ApplicationService.period(round, asOf), r.applicant(), academic, picks, r.resume(), r.essays(),
                ApplicationViews.essayWarnings(r.essays(), picks.stream().map(p -> p.institution().name()).toList()),
                r.pledge(), new Consents(r.consentCollect(), r.consentThirdParty()), r.signature(), approval,
                ApplicationViews.checklist(academic != null, picks, r.applicant(), r.pledge(), r.essays(),
                        r.consentCollect(), r.consentThirdParty(), r.signature(), approval.status(), props.essayMinChars()),
                r.counselCount(), r.fixReason(), r.createdAt(), r.updatedAt(), r.submittedAt(), r.receivedAt());
    }

    // ───────────── 매칭 · 선발 ─────────────

    @Transactional(readOnly = true)
    public PlacementBoard placement(AuthUser user) {
        Loaded l = load(repo.inScope(scope(user), rounds.current().id()).stream()
                .filter(r -> r.status() != Status.SUBMITTED).toList());
        List<PlacementRow> rows = new ArrayList<>();
        Map<Integer, Integer> load = new LinkedHashMap<>();
        Set<Integer> institutions = new LinkedHashSet<>();
        for (Row r : l.rows()) {
            JobInfo j = l.matchedJob(r);
            if (j != null && r.status() != Status.FIX_REQUESTED) {
                load.merge(j.id(), 1, Integer::sum);
                institutions.add(j.institution().id());
            }
            rows.add(new PlacementRow(r.id(), r.receiptNo(), r.status(), r.virtual(), ApplicationViews.student(r),
                    r.counselCount(), ApplicationViews.centerPicks(l.picksOf(r), l.jobs()), r.matchedRank(),
                    j == null ? null : new JobBrief(j.id(), j.title(), j.institution()),
                    r.interviewAt() == null ? null : new Interview(r.interviewAt(), r.interviewMode()), r.result(),
                    r.notifiedAt()));
        }
        List<JobLoad> jobs = load.entrySet().stream().map(e -> {
            JobInfo j = l.jobs().get(e.getKey());
            return new JobLoad(j.id(), j.title(), j.team(), j.institution(), j.headcount(), e.getValue());
        }).toList();
        List<Row> matched = l.rows().stream().filter(r -> r.status() == Status.MATCHED).toList();
        PlacementCounts counts = new PlacementCounts(
                (int) l.rows().stream().filter(r -> r.status() == Status.RECEIVED || r.status() == Status.MATCHED).count(),
                (int) l.rows().stream().filter(r -> r.status() == Status.FIX_REQUESTED).count(),
                (int) l.rows().stream().filter(r -> r.status() != Status.FIX_REQUESTED && r.matchedRank() != null).count(),
                institutions.size(), matched.size(),
                (int) matched.stream().filter(r -> r.interviewAt() != null).count(),
                (int) matched.stream().filter(r -> r.result() == Result.PASS).count(),
                (int) matched.stream().filter(r -> r.result() == Result.FAIL).count());
        return new PlacementBoard(max(matched.stream().map(Row::matchedAt)), max(matched.stream().map(Row::notifiedAt)),
                counts, rows, jobs);
    }

    private static OffsetDateTime max(java.util.stream.Stream<OffsetDateTime> times) {
        return times.filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
    }

    @Transactional
    public PlacementBoard match(AuthUser user, long applicationId, Integer rank) {
        Row r = inScope(user, applicationId, true);
        if (r.status() != Status.RECEIVED) {
            throw new ApiException(ErrorCode.STATE_CONFLICT, "접수 완료한 지원서만 매칭할 수 있어요(확정 뒤에는 못 바꿔요)");
        }
        List<Integer> ranks = repo.picks(List.of(r.id())).getOrDefault(r.id(), List.of()).stream().map(PickRow::rank)
                .toList();
        if (rank != null && !ranks.contains(rank)) {
            throw ApiException.invalid("rank", "이 지원서의 지망(" + ranks.stream().map(String::valueOf)
                    .collect(Collectors.joining("·")) + "지망) 중 하나");
        }
        if (repo.setMatchedRank(r.id(), rank, clock.instant()) != 1) {
            throw new ApiException(ErrorCode.STATE_CONFLICT, "지원서 상태가 바뀌었어요. 다시 불러 주세요");
        }
        return placement(user);
    }

    @Transactional
    public PlacementBoard confirm(AuthUser user) {
        Scope scope = scope(user);
        int roundId = rounds.current().id();
        List<Row> received = repo.inScope(scope, roundId).stream().filter(r -> r.status() == Status.RECEIVED).toList();
        long unmatched = received.stream().filter(r -> r.matchedRank() == null).count();
        if (unmatched > 0) {
            throw new ApiException(ErrorCode.STATE_CONFLICT, "접수 완료한 " + unmatched + "명의 매칭을 아직 고르지 않았어요");
        }
        if (received.isEmpty()) {
            throw new ApiException(ErrorCode.STATE_CONFLICT, "확정할 매칭이 없어요");
        }
        repo.confirmMatches(scope, roundId, clock.instant());
        return placement(user);
    }

    @Transactional
    public PlacementBoard select(AuthUser user, long applicationId, OffsetDateTime interviewAt, InterviewMode mode,
                                 Result result) {
        Row r = inScope(user, applicationId, true);
        if (r.status() != Status.MATCHED || r.notifiedAt() != null) {
            throw new ApiException(ErrorCode.STATE_CONFLICT, "매칭이 확정되고 결과를 아직 알리지 않은 학생만 넣을 수 있어요");
        }
        if (result == null) {
            throw ApiException.invalid("result", "WAIT·PASS·FAIL");
        }
        if (repo.setSelection(r.id(), interviewAt, mode, result, clock.instant()) != 1) {
            throw new ApiException(ErrorCode.STATE_CONFLICT, "매칭이 확정되고 결과를 아직 알리지 않은 학생만 넣을 수 있어요");
        }
        return placement(user);
    }

    @Transactional
    public PlacementBoard notifyResults(AuthUser user) {
        Scope scope = scope(user);
        int roundId = rounds.current().id();
        List<Row> pending = repo.inScope(scope, roundId).stream()
                .filter(r -> r.status() == Status.MATCHED && r.notifiedAt() == null).toList();
        long waiting = pending.stream().filter(r -> r.result() == Result.WAIT).count();
        if (waiting > 0) {
            throw new ApiException(ErrorCode.STATE_CONFLICT, "결과가 '대기'인 학생이 " + waiting + "명 있어요");
        }
        if (pending.isEmpty()) {
            throw new ApiException(ErrorCode.STATE_CONFLICT, "알릴 결과가 없어요");
        }
        repo.notifyResults(scope, roundId, clock.instant());
        return placement(user);
    }

    // ───────────── 마무리 ─────────────

    @Transactional(readOnly = true)
    public CloseBoard close(AuthUser user) {
        Loaded l = load(repo.inScope(scope(user), rounds.current().id()).stream().filter(TimelineService::passed).toList());
        List<Long> ids = l.rows().stream().map(Row::id).toList();
        Map<Long, CloseRow> closes = repo.closes(ids);
        Map<Long, ApprovalRow> approvals = repo.approvals(ids, ApprovalKind.CREDIT);
        List<InternshipDtos.CloseRow> rows = new ArrayList<>();
        Map<Integer, Boolean> institutionDone = new LinkedHashMap<>();
        int studentDocs = 0;
        for (Row r : l.rows()) {
            CloseRow c = closes.getOrDefault(r.id(), CloseRow.empty(r.id()));
            var approval = ApplicationViews.approval(approvals.get(r.id()), null, true);
            List<CloseItem> missing = missing(c, approval.status() == InternshipDtos.ApprovalStatus.APPROVED);
            JobInfo j = l.matchedJob(r);
            if (j == null) {
                // 매칭된 직무가 시드에서 빠졌다(지망 줄이 cascade로 지워짐). 마무리 표에서 뺀다
                continue;
            }
            boolean instDone = c.evaluation() != null && c.attendance() != null;
            institutionDone.merge(j.institution().id(), instDone, Boolean::logicalAnd);
            if (c.report() != null && c.credit() != null && c.survey() != null) {
                studentDocs++;
            }
            rows.add(new InternshipDtos.CloseRow(r.id(), r.virtual(), ApplicationViews.student(r),
                    new JobBrief(j.id(), j.title(), j.institution()),
                    new CloseDocuments(c.report(), c.credit(), c.survey(), c.evaluation(), c.attendance()), approval,
                    missing, missing.isEmpty()));
        }
        int institutionsDone = (int) institutionDone.values().stream().filter(Boolean::booleanValue).count();
        return new CloseBoard(max(closes.values().stream().map(CloseRow::remindedAt)),
                new CloseCounts(rows.size(), studentDocs, institutionDone.size(), institutionsDone,
                        (int) rows.stream().filter(InternshipDtos.CloseRow::ready).count()),
                rows);
    }

    static List<CloseItem> missing(CloseRow c, boolean approved) {
        List<CloseItem> out = new ArrayList<>();
        if (c.report() == null) {
            out.add(CloseItem.REPORT);
        }
        if (c.credit() == null) {
            out.add(CloseItem.CREDIT);
        }
        if (c.survey() == null) {
            out.add(CloseItem.SURVEY);
        }
        if (c.evaluation() == null) {
            out.add(CloseItem.EVALUATION);
        }
        if (c.attendance() == null) {
            out.add(CloseItem.ATTENDANCE);
        }
        if (!approved) {
            out.add(CloseItem.APPROVAL);
        }
        return out;
    }

    /** 기관 서류 받음 표시(null이면 그대로). 평가표·출근부를 모두 받으면 학점 인정 승인 링크를 만든다. */
    @Transactional
    public CloseBoard institutionDocuments(AuthUser user, long applicationId, Boolean evaluation, Boolean attendance) {
        Row r = inScope(user, applicationId, true);
        if (!TimelineService.passed(r)) {
            throw new ApiException(ErrorCode.STATE_CONFLICT, "합격을 알린 학생만 마무리 서류를 받아요");
        }
        Instant now = clock.instant();
        if (evaluation != null) {
            repo.setClose(r.id(), "evaluation_received_at", evaluation ? now : null);
        }
        if (attendance != null) {
            repo.setClose(r.id(), "attendance_received_at", attendance ? now : null);
        }
        CloseRow c = repo.closes(List.of(r.id())).get(r.id());
        if (c != null && c.evaluation() != null && c.attendance() != null
                && repo.approval(r.id(), ApprovalKind.CREDIT).isEmpty()) {
            repo.requestApproval(r.id(), ApprovalKind.CREDIT, ApplicationViews.token(), null, now);
        }
        return close(user);
    }

    @Transactional
    public Reminded remind(AuthUser user) {
        Instant now = clock.instant();
        int n = 0;
        for (InternshipDtos.CloseRow row : close(user).rows()) {
            if (row.missing().stream().anyMatch(m -> m == CloseItem.REPORT || m == CloseItem.CREDIT || m == CloseItem.SURVEY)) {
                repo.setClose(row.applicationId(), "reminded_at", now);
                n++;
            }
        }
        return new Reminded(n, n == 0 ? null : Times.kst(now));
    }

    /** 학점 인정·장학금 명단(서류가 다 모이고 학과(부)장이 승인한 학생). 엑셀이 한글을 읽게 BOM을 붙인다. */
    @Transactional(readOnly = true)
    public byte[] creditsCsv(AuthUser user) {
        CloseBoard board = close(user);
        Map<Long, Row> rows = repo.inScope(scope(user), rounds.current().id()).stream()
                .collect(Collectors.toMap(Row::id, r -> r));
        Map<Integer, JobInfo> jobs = repo.jobs(board.rows().stream().map(r -> r.job().jobId()).distinct().toList());
        StringBuilder b = new StringBuilder("﻿");
        b.append(String.join(",", "접수번호", "학번", "성명", "학과", "학년", "실습기관", "부서", "직무", "교과목", "학점",
                "실습 시작", "실습 끝", "대학 지원금 월액(원)", "지원금 최대 개월", "가상")).append("\r\n");
        for (InternshipDtos.CloseRow c : board.rows()) {
            if (!c.ready()) {
                continue;
            }
            Row r = rows.get(c.applicationId());
            JobInfo j = jobs.get(c.job().jobId());
            b.append(String.join(",", csv(r.receiptNo()), csv(r.applicant().studentNo()), csv(r.applicant().nameKo()),
                    csv(r.departmentName()), csv(r.grade() == null ? "" : r.grade().toString()),
                    csv(j.institution().name()), csv(j.team()), csv(j.title()), csv(props.course()),
                    String.valueOf(props.credits()), csv(String.valueOf(j.periodStart())),
                    csv(String.valueOf(j.periodEnd())), String.valueOf(props.scholarshipMonthly()),
                    String.valueOf(props.scholarshipMonths()), r.virtual() ? "Y" : "N")).append("\r\n");
        }
        return b.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** CSV 칸: 따옴표로 감싸고, 수식으로 읽힐 수 있는 첫 글자(= + - @)는 작은따옴표를 붙인다. */
    static String csv(String v) {
        String s = v == null ? "" : v;
        if (!s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0) {
            s = "'" + s;
        }
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
}
