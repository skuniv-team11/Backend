package kr.ac.skuniv.coopradar.internship;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import kr.ac.skuniv.coopradar.auth.AuthUser;
import kr.ac.skuniv.coopradar.common.ApiErrorHandler.FieldProblem;
import kr.ac.skuniv.coopradar.common.ApiException;
import kr.ac.skuniv.coopradar.common.ErrorCode;
import kr.ac.skuniv.coopradar.common.Times;
import kr.ac.skuniv.coopradar.eligibility.EligibilityDtos.Verdict;
import kr.ac.skuniv.coopradar.eligibility.EligibilityService;
import kr.ac.skuniv.coopradar.eligibility.ProfileInput;
import kr.ac.skuniv.coopradar.internship.ApplicationRepository.ApprovalRow;
import kr.ac.skuniv.coopradar.internship.ApplicationRepository.Demo;
import kr.ac.skuniv.coopradar.internship.ApplicationRepository.JobInfo;
import kr.ac.skuniv.coopradar.internship.ApplicationRepository.PickRow;
import kr.ac.skuniv.coopradar.internship.ApplicationRepository.RankedJob;
import kr.ac.skuniv.coopradar.internship.ApplicationRepository.Row;
import kr.ac.skuniv.coopradar.internship.ApplicationViews.Basis;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.Academic;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.Applicant;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.Application;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.Approval;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.ApprovalKind;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.ApprovalStatus;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.Award;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.Career;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.Certificate;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.Check;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.Consents;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.Item;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.Period;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.Pick;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.Resume;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.Status;
import kr.ac.skuniv.coopradar.job.RoundRef;
import kr.ac.skuniv.coopradar.me.ProfileRepository;
import kr.ac.skuniv.coopradar.me.SavedProfile;
import kr.ac.skuniv.coopradar.reference.ReferenceDtos.CurrentRound;
import kr.ac.skuniv.coopradar.reference.ReferenceDates;
import kr.ac.skuniv.coopradar.reference.ReferenceDates.AsOf;
import kr.ac.skuniv.coopradar.reference.RoundService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 학생 지원서(#37~#41, ADR-0033). 별지 제5호 한 부가 1~3지망에 같이 간다.
 * <ul>
 *   <li>1~3지망은 담은 직무 순위(#22)에서 순위 값 그대로 온다(2·3지망만 정했으면 2·3, 1지망이 없으면 낼 수 없다).
 *       내기 전에는 저장한 프로필로 다시 판정해 보여 주고, 낼 때 그 값을 고정한다</li>
 *   <li>저장은 개인정보 수집·이용 동의 뒤에만(동의가 없으면 아무것도 저장하지 않는다)</li>
 *   <li>학과(부)장 승인은 지금 내용·지망·학적의 해시로 묶는다({@link Basis}). 승인 뒤 바뀌면 STALE → 다시 요청</li>
 *   <li>내기: 신청 기간(체험 학생은 기준일) · 빠진 칸 없음 · 승인됨. 보완 요청을 받으면 고쳐서 다시 낸다(같은 접수번호,
 *       신청 기간이 끝났어도 된다)</li>
 *   <li>학생이 바꾸는 요청은 지원서 행을 잠근다(두 번 누름·자동 저장과 겹쳐도 500이 나지 않게)</li>
 * </ul>
 */
@Service
public class ApplicationService {

    private final ApplicationRepository repo;
    private final ProfileRepository profiles;
    private final EligibilityService eligibility;
    private final RoundService rounds;
    private final InternshipProperties props;
    private final ReferenceDates dates;
    private final Clock clock;

    public ApplicationService(ApplicationRepository repo, ProfileRepository profiles, EligibilityService eligibility,
                              RoundService rounds, InternshipProperties props, ReferenceDates dates, Clock clock) {
        this.repo = repo;
        this.profiles = profiles;
        this.eligibility = eligibility;
        this.rounds = rounds;
        this.props = props;
        this.dates = dates;
        this.clock = clock;
    }

    /** 진행 기준일: 체험 학생은 계정의 기준일, 아니면 오늘(한국 시간, {@link ReferenceDates#progress}). */
    LocalDate today(Demo demo) {
        return dates.progress(null, demo.today());
    }

    @Transactional(readOnly = true)
    public Application get(AuthUser user) {
        return view(user, repo.byUser(user.id(), rounds.current().id()).orElse(null));
    }

    @Transactional
    public Application save(AuthUser user, ApplicationRequest req) {
        if (req.consents() == null || !Boolean.TRUE.equals(req.consents().collect())) {
            throw new ApiException(ErrorCode.CONSENT_REQUIRED, "개인정보 수집·이용에 동의해야 지원서를 저장할 수 있어요");
        }
        CurrentRound round = rounds.current();
        Demo demo = repo.demo(user.id());
        Row row = repo.byUserForUpdate(user.id(), round.id())
                .orElseGet(() -> repo.insertDraft(user.id(), round.id(), demo.group()));
        if (!ApplicationViews.editable(row.status())) {
            throw new ApiException(ErrorCode.APPLICATION_LOCKED, "낸 지원서는 센터가 보완을 요청했을 때만 고칠 수 있어요");
        }
        if (repo.saveForm(row.id(), applicant(req), resume(req), essays(req), Boolean.TRUE.equals(req.pledge()), true,
                Boolean.TRUE.equals(req.consents().thirdParty()), blankToNull(req.signature()), clock.instant()) != 1) {
            throw new ApiException(ErrorCode.APPLICATION_LOCKED, "낸 지원서는 센터가 보완을 요청했을 때만 고칠 수 있어요");
        }
        return view(user, repo.byId(row.id()).orElseThrow());
    }

    /**
     * 지원서 지우기(내기 전까지). 없어도 204. 한 번 낸 지원서(접수번호가 있음 — 보완 요청 중·매칭 뒤 포함)는 지우지 못한다:
     * 센터가 이미 받은 서류이고, 지우면 마지막 접수번호가 다음 학생에게 다시 매겨진다(ADR-0037). 탈퇴는 예외로 함께 지운다(ADR-0008).
     */
    @Transactional
    public void delete(AuthUser user) {
        repo.byUserForUpdate(user.id(), rounds.current().id()).ifPresent(r -> {
            if (r.receiptNo() != null) {
                throw new ApiException(ErrorCode.APPLICATION_LOCKED, "낸 지원서는 지울 수 없어요. 센터에 문의해 주세요");
            }
            repo.delete(r.id());
        });
    }

    /** 학과(부)장 승인 링크 만들기(지금 내용으로). 다시 부르면 새 링크로 바뀌고 승인 전으로 돌아간다. */
    @Transactional
    public Application requestApproval(AuthUser user) {
        Row row = mine(user);
        if (!ApplicationViews.editable(row.status())) {
            throw new ApiException(ErrorCode.APPLICATION_LOCKED, "이미 낸 지원서예요");
        }
        Optional<SavedProfile> saved = profiles.findSaved(user.id(), user.guest());
        if (saved.isEmpty()) {
            throw new ApiException(ErrorCode.PROFILE_NOT_FOUND, "학과를 알 수 있게 프로필을 먼저 저장해 주세요");
        }
        String hash = ApplicationViews.contentHash(row, liveBasis(user.id(), saved));
        repo.requestApproval(row.id(), ApprovalKind.APPLICATION, ApplicationViews.token(), hash, clock.instant());
        return view(user, row);
    }

    @Transactional
    public Application submit(AuthUser user) {
        CurrentRound round = rounds.current();
        Demo demo = repo.demo(user.id());
        Row row = mine(user);
        if (!ApplicationViews.editable(row.status())) {
            throw new ApiException(ErrorCode.APPLICATION_LOCKED, "이미 낸 지원서예요");
        }
        // 보완 요청을 받은 지원서는 신청 기간이 끝났어도 다시 낸다(센터의 보완 요청에는 날짜 제한이 없어서)
        boolean resubmit = row.status() == Status.FIX_REQUESTED && row.receiptNo() != null;
        if (!resubmit && !period(round, today(demo)).open()) {
            throw new ApiException(ErrorCode.APPLICATION_CLOSED, "신청 기간(%s~%s)이 아니에요".formatted(
                    round.recruitStart(), round.recruitEnd()));
        }
        Application view = view(user, row);
        List<FieldProblem> missing = view.checklist().stream().filter(c -> !c.done())
                .map(c -> new FieldProblem(c.item().name(), MISSING.get(c.item()))).toList();
        if (!missing.isEmpty()) {
            throw new ApiException(ErrorCode.APPLICATION_INCOMPLETE, "아직 채우지 않은 것이 있어요", missing);
        }
        SavedProfile p = profiles.findSaved(user.id(), user.guest()).orElseThrow();
        repo.replacePicks(row.id(), view.picks().stream().map(k -> new PickRow(k.rank(), k.jobId(), k.verdict())).toList());
        Instant now = clock.instant();
        // 체험 학생은 기준일에 낸 것으로 남긴다(낸 날로 마감 여부를 다시 보기 때문에)
        Instant submittedAt = demo.today() == null ? now
                : demo.today().atTime(LocalTime.now(clock.withZone(Times.KST))).atZone(Times.KST).toInstant();
        String receipt = row.receiptNo() != null ? row.receiptNo()
                : repo.nextReceiptNo(round.id(), demo.group(), round.termCode());
        if (repo.submit(row.id(), receipt, p.departmentId(), p.grade(), p.completedSemesters(), p.gpa(),
                p.graduationExpected(), submittedAt, now) != 1) {
            throw new ApiException(ErrorCode.APPLICATION_LOCKED, "이미 낸 지원서예요");
        }
        Application after = view(user, repo.byId(row.id()).orElseThrow());
        // 위에서 확인한 뒤 다른 탭에서 프로필이 바뀌었으면 고정한 학적이 승인한 값과 다르다 — 되돌리고 다시 승인받게 한다
        if (after.approval().status() != ApprovalStatus.APPROVED) {
            throw new ApiException(ErrorCode.STATE_CONFLICT, "내는 사이 프로필이 바뀌었어요. 학과(부)장 승인을 다시 받아 주세요");
        }
        return after;
    }

    static final Map<Item, String> MISSING = Map.of(
            Item.PROFILE, "프로필(학과·학년·평점)을 저장해 주세요",
            Item.PICKS, "1지망이 있고, 1~3지망이 모두 지원 가능(또는 확인 필요)이고 마감 전이어야 해요",
            Item.APPLICANT, "성명(한글·영문)·생년월일·성별·연락처·현 주소·학번을 채워 주세요",
            Item.PLEDGE, "서약에 동의해 주세요",
            Item.ESSAYS, "자기소개서 4문항을 각각 300자 이상 써 주세요",
            Item.CONSENTS, "개인정보 수집·이용과 제3자 제공에 모두 동의해 주세요",
            Item.SIGNATURE, "본인 서명(이름)을 적어 주세요",
            Item.APPROVAL, "학과(부)장 승인을 받아 주세요(승인 뒤 내용을 바꾸면 다시 받아야 해요)");

    /** 내 지원서(잠금). */
    private Row mine(AuthUser user) {
        return repo.byUserForUpdate(user.id(), rounds.current().id())
                .orElseThrow(() -> new ApiException(ErrorCode.APPLICATION_NOT_FOUND, "저장한 지원서가 없어요"));
    }

    // ───────────── 응답 ─────────────

    Application view(AuthUser user, Row row) {
        CurrentRound round = rounds.current();
        Demo demo = repo.demo(user.id());
        LocalDate asOf = today(demo);
        Status status = row == null ? Status.NONE : row.status();
        Basis basis;
        List<Pick> picks;
        if (ApplicationViews.editable(status)) {
            Optional<SavedProfile> saved = profiles.findSaved(user.id(), user.guest());
            basis = liveBasis(user.id(), saved);
            // 보완 요청 중이면 처음 낸 지망은 낸 날 기준으로 본다(그날 열려 있었으면 그대로 다시 낸다).
            // 보완 중에 새로 넣은 지망은 오늘 기준 — 마감된 직무를 새로 넣어 낼 수 없게
            Set<Integer> firstPicks = Set.of();
            LocalDate firstOn = asOf;
            if (row != null && row.status() == Status.FIX_REQUESTED && row.submittedAt() != null) {
                firstPicks = repo.picks(List.of(row.id())).getOrDefault(row.id(), List.of()).stream()
                        .map(PickRow::jobId).collect(Collectors.toSet());
                firstOn = row.submittedAt().toLocalDate().isBefore(asOf) ? row.submittedAt().toLocalDate() : asOf;
            }
            picks = livePicks(round, basis.picks(), saved, ReferenceDates.on(asOf, round), firstPicks,
                    ReferenceDates.on(firstOn, round));
        } else {
            List<PickRow> rows = repo.picks(List.of(row.id())).getOrDefault(row.id(), List.of());
            basis = ApplicationViews.submittedBasis(row, rows);
            picks = ApplicationViews.snapshotPicks(rows, repo.jobs(basis.jobIds()),
                    ReferenceDates.on(ApplicationViews.submittedOn(row, asOf), round));
        }
        Academic academic = basis.academic();
        Applicant applicant = row == null ? new Applicant(null, null, null, null, null, demo.email(), null, null, null)
                : row.applicant();
        Resume resume = row == null ? Resume.EMPTY : row.resume();
        List<String> essays = row == null ? List.of("", "", "", "") : row.essays();
        boolean pledge = row != null && row.pledge();
        boolean collect = row != null && row.consentCollect();
        boolean third = row != null && row.consentThirdParty();
        String signature = row == null ? null : row.signature();
        ApprovalRow approvalRow = row == null ? null : repo.approval(row.id(), ApprovalKind.APPLICATION).orElse(null);
        String hash = row == null ? null : ApplicationViews.contentHash(row, basis);
        Approval approval = ApplicationViews.approval(approvalRow, hash, true);
        List<Check> checklist = ApplicationViews.checklist(academic != null, picks, applicant, pledge, essays, collect,
                third, signature, approval.status(), props.essayMinChars());
        return new Application(row == null ? null : row.id(), status, row == null ? null : row.receiptNo(),
                row != null && row.virtual(), new RoundRef(round.id(), round.termCode()), asOf, period(round, asOf),
                applicant, academic, picks, resume, essays,
                ApplicationViews.essayWarnings(essays, picks.stream().map(p -> p.institution().name()).toList()),
                pledge, new Consents(collect, third), signature, approval, checklist,
                row == null ? 0 : row.counselCount(), row == null ? null : row.fixReason(),
                row == null ? null : row.createdAt(), row == null ? null : row.updatedAt(),
                row == null ? null : row.submittedAt(), row == null ? null : row.receivedAt());
    }

    /** 고칠 수 있는 지원서의 지망·학적: 담은 직무 순위와 저장한 프로필. */
    private Basis liveBasis(long userId, Optional<SavedProfile> saved) {
        return new Basis(repo.rankedPlan(userId), saved.map(ApplicationService::academic).orElse(null));
    }

    /**
     * 승인 해시에 쓰는 지망·학적. 학생 화면·승인 화면(#42)·센터 화면·타임라인이 모두 이것을 쓴다.
     * 고칠 수 있으면 담은 순위·저장한 프로필, 낸 뒤면 낸 지망·고정한 학적. 가상 지원자(계정 없음)는 늘 낸 뒤 값이다.
     */
    Basis basis(Row row) {
        if (!ApplicationViews.editable(row.status()) || row.userId() == null) {
            return ApplicationViews.submittedBasis(row, repo.picks(List.of(row.id())).getOrDefault(row.id(), List.of()));
        }
        return liveBasis(row.userId(), profiles.findSaved(row.userId(), false));
    }

    static Academic academic(SavedProfile p) {
        return new Academic(p.department(), p.grade(), p.completedSemesters(), p.gpa(), p.graduationExpected());
    }

    /**
     * 담은 직무 순위 → 1~3지망(순위 값 그대로, 저장한 프로필로 판정, 없으면 verdict null).
     *
     * @param firstPicks 보완 요청 중일 때 처음 낸 지망 직무(이 직무는 firstOn 기준으로 마감을 본다)
     */
    private List<Pick> livePicks(CurrentRound round, List<RankedJob> ranked, Optional<SavedProfile> saved, AsOf asOf,
                                 Set<Integer> firstPicks, AsOf firstOn) {
        if (ranked.isEmpty()) {
            return List.of();
        }
        Map<Integer, Verdict> verdicts = new HashMap<>();
        saved.ifPresent(p -> eligibility.judgeAll(round.id(), new ProfileInput(p.departmentId(), p.grade(),
                        p.completedSemesters(), p.gpa(), p.graduationExpected(), p.interestText(), p.homeAreaCode(),
                        p.certificates()))
                .forEach(j -> verdicts.put(j.requirement().jobId(), j.result().verdict())));
        Map<Integer, JobInfo> jobs = repo.jobs(ranked.stream().map(RankedJob::jobId).toList());
        List<Pick> out = new ArrayList<>();
        for (RankedJob r : ranked) {
            JobInfo j = jobs.get(r.jobId());
            if (j != null) {
                out.add(new Pick(r.rank(), j.id(), j.title(), j.team(), j.institution(), verdicts.get(j.id()),
                        ApplicationViews.closed(j, firstPicks.contains(j.id()) ? firstOn : asOf)));
            }
        }
        return out;
    }

    static Period period(CurrentRound round, LocalDate asOf) {
        boolean open = round.recruitStart() != null && round.recruitEnd() != null
                && !asOf.isBefore(round.recruitStart()) && !asOf.isAfter(round.recruitEnd());
        return new Period(round.recruitStart(), round.recruitEnd(), open);
    }

    // ───────────── 본문 → 값 ─────────────

    private static Applicant applicant(ApplicationRequest req) {
        var a = req.applicant();
        if (a == null) {
            return Applicant.EMPTY;
        }
        return new Applicant(blankToNull(a.nameKo()), blankToNull(a.nameEn()), a.birthDate(), blankToNull(a.gender()),
                blankToNull(a.phone()), blankToNull(a.email()), blankToNull(a.address()), blankToNull(a.studentNo()),
                blankToNull(a.minorMajor()));
    }

    private static Resume resume(ApplicationRequest req) {
        var r = req.resume();
        if (r == null) {
            return Resume.EMPTY;
        }
        return new Resume(
                r.certificates() == null ? List.of() : r.certificates().stream()
                        .map(c -> new Certificate(c.kind() == null ? "CERTIFICATE" : c.kind(), blankToNull(c.name()),
                                blankToNull(c.issuer()), blankToNull(c.acquiredOn()))).toList(),
                r.awards() == null ? List.of() : r.awards().stream()
                        .map(w -> new Award(blankToNull(w.name()), blankToNull(w.issuer()), blankToNull(w.awardedOn()),
                                blankToNull(w.detail()))).toList(),
                r.careers() == null ? List.of() : r.careers().stream()
                        .map(c -> new Career(blankToNull(c.period()), blankToNull(c.company()), blankToNull(c.role()),
                                blankToNull(c.note()))).toList());
    }

    private static List<String> essays(ApplicationRequest req) {
        if (req.essays() == null) {
            return List.of("", "", "", "");
        }
        return req.essays().stream().map(e -> e == null ? "" : e.strip()).toList();
    }

    static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    /** 승인 상태만(타임라인용). */
    ApprovalStatus approvalStatus(Row row) {
        return ApplicationViews.approvalStatus(repo.approval(row.id(), ApprovalKind.APPLICATION).orElse(null),
                ApplicationViews.contentHash(row, basis(row)));
    }
}
