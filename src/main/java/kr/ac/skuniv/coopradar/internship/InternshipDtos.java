package kr.ac.skuniv.coopradar.internship;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import kr.ac.skuniv.coopradar.eligibility.EligibilityDtos.Verdict;
import kr.ac.skuniv.coopradar.job.InstitutionRef;
import kr.ac.skuniv.coopradar.job.RoundRef;
import kr.ac.skuniv.coopradar.job.Stipend;
import kr.ac.skuniv.coopradar.me.ProfileView.DepartmentRef;
import kr.ac.skuniv.coopradar.reference.ReferenceDtos.Phase;
import kr.ac.skuniv.coopradar.reference.ReferenceDtos.Stage;

/**
 * 현장실습 진행 응답 모양(docs/api #37~#58, ADR-0033): 지원서 · 학과(부)장 승인 · 내 현장실습 · 센터 접수함 · 매칭·선발 · 마무리.
 * 필드 이름이 JSON 이름이다.
 */
public final class InternshipDtos {

    private InternshipDtos() {
    }

    /** 지원서 상태. NONE은 아직 저장한 적이 없음(응답에서만). */
    public enum Status { NONE, DRAFT, SUBMITTED, RECEIVED, FIX_REQUESTED, MATCHED }

    public enum ApprovalStatus { NONE, REQUESTED, APPROVED, STALE }

    public enum ApprovalKind { APPLICATION, CREDIT }

    /** 지원서를 내기 전에 확인하는 것(빠지면 내지 못한다). */
    public enum Item { PROFILE, PICKS, APPLICANT, PLEDGE, ESSAYS, CONSENTS, SIGNATURE, APPROVAL }

    public enum InterviewMode { IN_PERSON, ONLINE, PHONE }

    public enum Result { WAIT, PASS, FAIL }

    public enum StageState { DONE, NOW, NEXT }

    public enum NextKind { START, END }

    /** 학생이 내는 마무리 서류. */
    public enum StudentDocument { REPORT, CREDIT, SURVEY }

    /** 마무리에서 빠진 것. */
    public enum CloseItem { REPORT, CREDIT, SURVEY, EVALUATION, ATTENDANCE, APPROVAL }

    public enum GuestStage { APPLYING, PRACTICING, DONE }

    public enum DemoStep { RECEIVED, MATCHED, SELECTED, CLOSING }

    // ───────────── 지원서 ─────────────

    public record Applicant(String nameKo, String nameEn, LocalDate birthDate, String gender, String phone, String email,
                            String address, String studentNo, String minorMajor) {

        static final Applicant EMPTY = new Applicant(null, null, null, null, null, null, null, null, null);
    }

    public record Academic(DepartmentRef department, int grade, int completedSemesters, BigDecimal gpa,
                           boolean graduationExpected) {
    }

    public record Certificate(String kind, String name, String issuer, String acquiredOn) {
    }

    public record Award(String name, String issuer, String awardedOn, String detail) {
    }

    public record Career(String period, String company, String role, String note) {
    }

    public record Resume(List<Certificate> certificates, List<Award> awards, List<Career> careers) {

        static final Resume EMPTY = new Resume(List.of(), List.of(), List.of());
    }

    public record Consents(boolean collect, boolean thirdParty) {
    }

    /**
     * 1~3지망. 내기 전에는 담은 직무 순위와 저장한 프로필로 다시 판정한 값, 낸 뒤에는 낼 때 값.
     *
     * @param verdict 저장한 프로필이 없으면 null
     * @param closed  기준일에 마감된 직무
     */
    public record Pick(int rank, int jobId, String title, String team, InstitutionRef institution, Verdict verdict,
                       boolean closed) {
    }

    public record EssayWarning(int index, List<String> institutions) {
    }

    /** @param token 학생에게만(학과 사무실에 전할 링크). 센터 응답은 null */
    public record Approval(ApprovalStatus status, String token, OffsetDateTime requestedAt, OffsetDateTime approvedAt) {

        static final Approval NONE = new Approval(ApprovalStatus.NONE, null, null, null);
    }

    public record Check(Item item, boolean done) {
    }

    public record Period(LocalDate startsOn, LocalDate endsOn, boolean open) {
    }

    public record Application(Long applicationId, Status status, String receiptNo, boolean virtual, RoundRef round,
                              LocalDate asOf, Period period, Applicant applicant, Academic academic, List<Pick> picks,
                              Resume resume, List<String> essays, List<EssayWarning> essayWarnings, boolean pledge,
                              Consents consents, String signature, Approval approval, List<Check> checklist,
                              int counselCount, String fixReason, OffsetDateTime createdAt, OffsetDateTime updatedAt,
                              OffsetDateTime submittedAt, OffsetDateTime receivedAt) {
    }

    // ───────────── 학과(부)장 승인 ─────────────

    public record ApprovalStudent(String nameKo, String studentNo, DepartmentRef department, Integer grade) {
    }

    public record ApprovalPick(int rank, String title, InstitutionRef institution) {
    }

    public record ApprovalPlacement(String title, String team, InstitutionRef institution, LocalDate periodStart,
                                    LocalDate periodEnd) {
    }

    public record ApprovalView(ApprovalKind kind, ApprovalStatus status, OffsetDateTime requestedAt,
                               OffsetDateTime approvedAt, ApprovalStudent student, RoundRef round,
                               List<ApprovalPick> picks, ApprovalPlacement placement) {
    }

    // ───────────── 회차 일정 · 내 현장실습 ─────────────

    public record TimelineStage(Stage code, Phase phase, LocalDate startsOn, LocalDate endsOn, boolean confirmed,
                                StageState state) {
    }

    public record Next(Stage code, NextKind kind, LocalDate on, int days) {
    }

    public record NcsRef(String code, String name) {
    }

    /** 담은 직무. rank null은 후보(지망 밖). */
    public record PlanPick(Integer rank, int jobId, String title, InstitutionRef institution, NcsRef ncs, boolean closed) {
    }

    public record ApplicationBrief(long applicationId, Status status, String receiptNo, OffsetDateTime submittedAt,
                                   Approval approval, String fixReason, int counselCount, boolean virtual) {
    }

    public record Interview(OffsetDateTime at, InterviewMode mode) {
    }

    public record WeekPlan(String weeks, String content) {
    }

    /**
     * @param day   실습 시작일을 1일로 센 오늘(시작 전이면 0, 끝난 뒤면 days)
     * @param week  1부터. 시작 전이면 0
     */
    public record Practice(LocalDate startsOn, LocalDate endsOn, int day, int days, int week, int weeks,
                           List<String> weekdays, String workHours, BigDecimal weeklyHours, Stipend stipend,
                           String courseName, int credits, WeekPlan currentPlan, WeekPlan nextPlan) {
    }

    /**
     * 매칭된 자리. result는 센터가 학생에게 알린 뒤에만(그 전에는 null).
     */
    public record Placement(int jobId, String title, String team, InstitutionRef institution, int rank,
                            OffsetDateTime matchedAt, Interview interview, Result result, OffsetDateTime notifiedAt,
                            Practice practice) {
    }

    public record Documents(OffsetDateTime report, OffsetDateTime credit, OffsetDateTime survey,
                            OffsetDateTime evaluation, OffsetDateTime attendance, Long careerReportId,
                            Approval creditApproval, OffsetDateTime remindedAt) {
    }

    public record Internship(LocalDate asOf, RoundRef round, List<TimelineStage> stages, Stage now, Next next,
                             List<PlanPick> picks, ApplicationBrief application, Placement placement,
                             Documents documents) {
    }

    // ───────────── 센터 ─────────────

    public record StudentBrief(String nameKo, DepartmentRef department, Integer grade, Integer completedSemesters,
                               BigDecimal gpa, Boolean graduationExpected) {
    }

    public record CenterPick(int rank, int jobId, String title, InstitutionRef institution, Verdict verdict) {
    }

    public record InboxRow(long applicationId, String receiptNo, OffsetDateTime submittedAt, Status status,
                           boolean virtual, StudentBrief student, int counselCount, List<CenterPick> picks) {
    }

    public record InboxCounts(int all, int submitted, int received, int fixRequested, int matched) {
    }

    public record Inbox(InboxCounts counts, List<InboxRow> items) {
    }

    public record JobBrief(int jobId, String title, InstitutionRef institution) {
    }

    public record PlacementRow(long applicationId, String receiptNo, Status status, boolean virtual,
                               StudentBrief student, int counselCount, List<CenterPick> picks, Integer matchedRank,
                               JobBrief matchedJob, Interview interview, Result result, OffsetDateTime notifiedAt) {
    }

    public record JobLoad(int jobId, String title, String team, InstitutionRef institution, int headcount, int matched) {
    }

    public record PlacementCounts(int received, int fixRequested, int ranked, int institutions, int matched,
                                  int interviewed, int pass, int fail) {
    }

    public record PlacementBoard(OffsetDateTime confirmedAt, OffsetDateTime notifiedAt, PlacementCounts counts,
                                 List<PlacementRow> applicants, List<JobLoad> jobs) {
    }

    public record CloseDocuments(OffsetDateTime report, OffsetDateTime credit, OffsetDateTime survey,
                                 OffsetDateTime evaluation, OffsetDateTime attendance) {
    }

    public record CloseRow(long applicationId, boolean virtual, StudentBrief student, JobBrief job,
                           CloseDocuments documents, Approval approval, List<CloseItem> missing, boolean ready) {
    }

    public record CloseCounts(int students, int studentDocsDone, int institutions, int institutionsDone, int ready) {
    }

    public record CloseBoard(OffsetDateTime remindedAt, CloseCounts counts, List<CloseRow> rows) {
    }

    public record Reminded(int reminded, OffsetDateTime remindedAt) {
    }

    public record Advanced(DemoStep to, int changed) {
    }

    /** 체험 계정 만들기 응답에 붙는 묶음 정보. */
    public record Demo(UUID group, LocalDate today) {
    }
}
