package kr.ac.skuniv.coopradar.internship;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import kr.ac.skuniv.coopradar.internship.ApplicationRepository.Row;
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
import kr.ac.skuniv.coopradar.reference.RoundService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 학생 지원서(#37~#41, ADR-0033). 별지 제5호 한 부가 1~3지망에 같이 간다.
 * <ul>
 *   <li>1~3지망은 담은 직무 순위(#22)에서 온다. 내기 전에는 저장한 프로필로 다시 판정해 보여 주고, 낼 때 그 값을 고정한다</li>
 *   <li>저장은 개인정보 수집·이용 동의 뒤에만(동의가 없으면 아무것도 저장하지 않는다)</li>
 *   <li>학과(부)장 승인은 지금 내용의 해시로 묶는다. 승인 뒤 내용·지망이 바뀌면 STALE → 다시 요청</li>
 *   <li>내기: 신청 기간(체험 학생은 기준일) · 빠진 칸 없음 · 승인됨. 보완 요청을 받으면 고쳐서 다시 낸다(같은 접수번호)</li>
 * </ul>
 */
@Service
public class ApplicationService {

    private final ApplicationRepository repo;
    private final ProfileRepository profiles;
    private final EligibilityService eligibility;
    private final RoundService rounds;
    private final InternshipProperties props;
    private final Clock clock;

    public ApplicationService(ApplicationRepository repo, ProfileRepository profiles, EligibilityService eligibility,
                              RoundService rounds, InternshipProperties props, Clock clock) {
        this.repo = repo;
        this.profiles = profiles;
        this.eligibility = eligibility;
        this.rounds = rounds;
        this.props = props;
        this.clock = clock;
    }

    /** 기준일: 체험 학생은 계정의 기준일, 아니면 오늘(한국 시간). */
    LocalDate today(Demo demo) {
        return demo.today() != null ? demo.today() : LocalDate.now(clock.withZone(Times.KST));
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
        Row row = repo.byUser(user.id(), round.id()).orElse(null);
        if (row != null && !ApplicationViews.editable(row.status())) {
            throw new ApiException(ErrorCode.APPLICATION_LOCKED, "낸 지원서는 센터가 보완을 요청했을 때만 고칠 수 있어요");
        }
        long id = row != null ? row.id() : repo.insertDraft(user.id(), round.id(), demo.group());
        repo.saveForm(id, applicant(req), resume(req), essays(req), Boolean.TRUE.equals(req.pledge()), true,
                Boolean.TRUE.equals(req.consents().thirdParty()), blankToNull(req.signature()), clock.instant());
        return view(user, repo.byId(id).orElseThrow());
    }

    /** 지원서 지우기(매칭 확정 전까지). 없어도 204. */
    @Transactional
    public void delete(AuthUser user) {
        repo.byUser(user.id(), rounds.current().id()).ifPresent(r -> {
            if (r.status() == Status.MATCHED) {
                throw new ApiException(ErrorCode.APPLICATION_LOCKED, "매칭이 확정된 지원서는 지울 수 없어요. 센터에 문의해 주세요");
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
        if (profiles.findSaved(user.id(), user.guest()).isEmpty()) {
            throw new ApiException(ErrorCode.PROFILE_NOT_FOUND, "학과를 알 수 있게 프로필을 먼저 저장해 주세요");
        }
        String hash = ApplicationViews.contentHash(row, repo.rankedPlan(user.id()));
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
        if (!period(round, today(demo)).open()) {
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
        String receipt = row.receiptNo() != null ? row.receiptNo()
                : repo.nextReceiptNo(round.id(), demo.group(), round.termCode());
        repo.submit(row.id(), receipt, p.departmentId(), p.grade(), p.completedSemesters(), p.gpa(),
                p.graduationExpected(), now);
        return view(user, repo.byId(row.id()).orElseThrow());
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

    private Row mine(AuthUser user) {
        return repo.byUser(user.id(), rounds.current().id())
                .orElseThrow(() -> new ApiException(ErrorCode.APPLICATION_NOT_FOUND, "저장한 지원서가 없어요"));
    }

    // ───────────── 응답 ─────────────

    Application view(AuthUser user, Row row) {
        CurrentRound round = rounds.current();
        Demo demo = repo.demo(user.id());
        LocalDate asOf = today(demo);
        Status status = row == null ? Status.NONE : row.status();
        Optional<SavedProfile> saved = profiles.findSaved(user.id(), user.guest());
        List<Pick> picks;
        List<Integer> pickIds;
        Academic academic;
        if (ApplicationViews.editable(status)) {
            pickIds = repo.rankedPlan(user.id());
            picks = livePicks(round, pickIds, saved, asOf);
            academic = saved.map(p -> new Academic(p.department(), p.grade(), p.completedSemesters(), p.gpa(),
                    p.graduationExpected())).orElse(null);
        } else {
            List<PickRow> rows = repo.picks(List.of(row.id())).getOrDefault(row.id(), List.of());
            pickIds = rows.stream().map(PickRow::jobId).toList();
            picks = ApplicationViews.snapshotPicks(rows, repo.jobs(pickIds), asOf);
            academic = ApplicationViews.academic(row);
        }
        Applicant applicant = row == null ? new Applicant(null, null, null, null, null, demo.email(), null, null, null)
                : row.applicant();
        Resume resume = row == null ? Resume.EMPTY : row.resume();
        List<String> essays = row == null ? List.of("", "", "", "") : row.essays();
        boolean pledge = row != null && row.pledge();
        boolean collect = row != null && row.consentCollect();
        boolean third = row != null && row.consentThirdParty();
        String signature = row == null ? null : row.signature();
        ApprovalRow approvalRow = row == null ? null : repo.approval(row.id(), ApprovalKind.APPLICATION).orElse(null);
        String hash = row == null ? null : ApplicationViews.contentHash(row, pickIds);
        Approval approval = ApplicationViews.approval(approvalRow, hash, true);
        boolean hasProfile = ApplicationViews.editable(status) ? saved.isPresent() : academic != null;
        List<Check> checklist = ApplicationViews.checklist(hasProfile, picks, applicant, pledge, essays, collect, third,
                signature, approval.status(), props.essayMinChars());
        return new Application(row == null ? null : row.id(), status, row == null ? null : row.receiptNo(),
                row != null && row.virtual(), new RoundRef(round.id(), round.termCode()), asOf, period(round, asOf),
                applicant, academic, picks, resume, essays,
                ApplicationViews.essayWarnings(essays, picks.stream().map(p -> p.institution().name()).toList()),
                pledge, new Consents(collect, third), signature, approval, checklist,
                row == null ? 0 : row.counselCount(), row == null ? null : row.fixReason(),
                row == null ? null : row.createdAt(), row == null ? null : row.updatedAt(),
                row == null ? null : row.submittedAt(), row == null ? null : row.receivedAt());
    }

    /** 담은 직무 순위 → 1~3지망(저장한 프로필로 판정, 없으면 verdict null). */
    private List<Pick> livePicks(CurrentRound round, List<Integer> jobIds, Optional<SavedProfile> saved, LocalDate asOf) {
        if (jobIds.isEmpty()) {
            return List.of();
        }
        Map<Integer, Verdict> verdicts = new HashMap<>();
        saved.ifPresent(p -> eligibility.judgeAll(round.id(), new ProfileInput(p.departmentId(), p.grade(),
                        p.completedSemesters(), p.gpa(), p.graduationExpected(), p.interestText(), p.homeAreaCode(),
                        p.certificates()))
                .forEach(j -> verdicts.put(j.requirement().jobId(), j.result().verdict())));
        Map<Integer, JobInfo> jobs = repo.jobs(jobIds);
        List<Pick> out = new ArrayList<>();
        for (int i = 0; i < jobIds.size(); i++) {
            JobInfo j = jobs.get(jobIds.get(i));
            out.add(new Pick(i + 1, j.id(), j.title(), j.team(), j.institution(), verdicts.get(j.id()),
                    ApplicationViews.closed(j, asOf)));
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
    ApprovalStatus approvalStatus(Row row, long userId) {
        List<Integer> ids = ApplicationViews.editable(row.status()) ? repo.rankedPlan(userId)
                : repo.picks(List.of(row.id())).getOrDefault(row.id(), List.of()).stream().map(PickRow::jobId).toList();
        return ApplicationViews.approvalStatus(repo.approval(row.id(), ApprovalKind.APPLICATION).orElse(null),
                ApplicationViews.contentHash(row, ids));
    }
}
