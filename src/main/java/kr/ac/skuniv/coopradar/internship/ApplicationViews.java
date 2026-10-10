package kr.ac.skuniv.coopradar.internship;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import kr.ac.skuniv.coopradar.eligibility.EligibilityDtos.Verdict;
import kr.ac.skuniv.coopradar.internship.ApplicationRepository.ApprovalRow;
import kr.ac.skuniv.coopradar.internship.ApplicationRepository.JobInfo;
import kr.ac.skuniv.coopradar.internship.ApplicationRepository.PickRow;
import kr.ac.skuniv.coopradar.internship.ApplicationRepository.RankedJob;
import kr.ac.skuniv.coopradar.internship.ApplicationRepository.Row;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.Academic;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.Applicant;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.Approval;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.ApprovalStatus;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.CenterPick;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.Check;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.EssayWarning;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.Item;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.Pick;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.Resume;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.Status;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.StudentBrief;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** 지원서 응답을 만드는 규칙(DB 없이). 빠진 칸 · 승인 상태 · 자기소개서 기관 이름 경고 · 내용 해시 · 승인 링크 값. */
final class ApplicationViews {

    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern CORP = Pattern.compile("주식회사|\\(주\\)|㈜|\\([^)]*\\)");

    private ApplicationViews() {
    }

    /** 수정할 수 있는 상태(내기 전 · 보완 요청). 이때 1~3지망은 담은 직무 순위를 그대로 보여 준다. */
    static boolean editable(Status s) {
        return s == Status.NONE || s == Status.DRAFT || s == Status.FIX_REQUESTED;
    }

    /** 승인 링크 값(128비트 무작위, 16진 32자). */
    static String token() {
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    /**
     * 학과(부)장이 보는 지망·학적. 고칠 수 있는 상태면 담은 직무 순위와 저장한 프로필, 낸 뒤면 낸 지망과 그때 고정한 학적이다.
     * 학생 화면·승인 화면·센터 화면이 모두 이것으로 해시를 만든다(한 곳만 바뀌어 APPROVED와 STALE이 갈라지지 않게).
     */
    record Basis(List<RankedJob> picks, Academic academic) {

        List<Integer> jobIds() {
            return picks.stream().map(RankedJob::jobId).toList();
        }
    }

    /**
     * 학과(부)장이 본 내용의 해시: 신청서·이력서·자기소개서·서약·동의·서명·1~3지망(순위와 직무)·학적(학과·학년·이수 학기·평점·
     * 졸업예정). 바뀌면 승인을 다시 받아야 한다.
     */
    static String contentHash(Applicant a, Resume resume, List<String> essays, boolean pledge, boolean collect,
                              boolean thirdParty, String signature, Basis basis) {
        List<List<Integer>> picks = basis.picks().stream().map(p -> List.of(p.rank(), p.jobId())).toList();
        Academic ac = basis.academic();
        List<Object> academic = ac == null ? List.of() : java.util.Arrays.asList(
                ac.department() == null ? null : ac.department().id(), ac.grade(), ac.completedSemesters(),
                ac.gpa() == null ? null : ac.gpa().stripTrailingZeros().toPlainString(), ac.graduationExpected());
        String canonical = JSON.writeValueAsString(List.of(
                a == null ? Applicant.EMPTY : a, resume == null ? Resume.EMPTY : resume, essays, pledge, collect,
                thirdParty, signature == null ? "" : signature, picks, academic));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String contentHash(Row r, Basis basis) {
        return contentHash(r.applicant(), r.resume(), r.essays(), r.pledge(), r.consentCollect(), r.consentThirdParty(),
                r.signature(), basis);
    }

    /** 낸 지원서의 지망·학적(행에 고정한 값). */
    static Basis submittedBasis(Row r, List<PickRow> picks) {
        return new Basis(picks.stream().map(p -> new RankedJob(p.rank(), p.jobId())).toList(), academic(r));
    }

    static ApprovalStatus approvalStatus(ApprovalRow approval, String currentHash) {
        if (approval == null) {
            return ApprovalStatus.NONE;
        }
        if (approval.contentHash() != null && !approval.contentHash().equals(currentHash)) {
            return ApprovalStatus.STALE;
        }
        return approval.approvedAt() == null ? ApprovalStatus.REQUESTED : ApprovalStatus.APPROVED;
    }

    static Approval approval(ApprovalRow approval, String currentHash, boolean withToken) {
        if (approval == null) {
            return Approval.NONE;
        }
        return new Approval(approvalStatus(approval, currentHash), withToken ? approval.token() : null,
                approval.requestedAt(), approval.approvedAt());
    }

    /** 내기 전에 볼 것. profile은 저장한 프로필(또는 낸 뒤 학적)이 있는지. */
    static List<Check> checklist(boolean profile, List<Pick> picks, Applicant a, boolean pledge, List<String> essays,
                                 boolean collect, boolean thirdParty, String signature, ApprovalStatus approval,
                                 int essayMinChars) {
        boolean picksOk = picks.stream().anyMatch(p -> p.rank() == 1)
                && picks.stream().allMatch(p -> p.verdict() != null && p.verdict() != Verdict.INELIGIBLE && !p.closed());
        return List.of(
                new Check(Item.PROFILE, profile),
                new Check(Item.PICKS, picksOk),
                new Check(Item.APPLICANT, applicantDone(a)),
                new Check(Item.PLEDGE, pledge),
                new Check(Item.ESSAYS, essays.size() == 4 && essays.stream().allMatch(e -> chars(e) >= essayMinChars)),
                new Check(Item.CONSENTS, collect && thirdParty),
                new Check(Item.SIGNATURE, signature != null && signature.strip().length() >= 2),
                new Check(Item.APPROVAL, approval == ApprovalStatus.APPROVED));
    }

    static boolean applicantDone(Applicant a) {
        return a != null && filled(a.nameKo()) && filled(a.nameEn()) && a.birthDate() != null && filled(a.gender())
                && filled(a.phone()) && filled(a.address()) && filled(a.studentNo());
    }

    /** 자기소개서 글자 수(앞뒤 공백 빼고, 한글 한 자 = 1). */
    static int chars(String s) {
        return s == null ? 0 : s.strip().codePointCount(0, s.strip().length());
    }

    /**
     * 자기소개서에 1~3지망 기관 이름이 들어 있으면 알려 준다(지원서 한 부가 세 기관에 같이 가서).
     * 기관 이름에서 '주식회사'·'(주)'·괄호 안을 뺀 두 글자 이상이 글에 있으면 잡는다.
     */
    static List<EssayWarning> essayWarnings(List<String> essays, List<String> institutionNames) {
        List<String> cores = institutionNames.stream().map(ApplicationViews::coreName).filter(n -> n.length() >= 2)
                .distinct().toList();
        List<EssayWarning> out = new ArrayList<>();
        for (int i = 0; i < essays.size(); i++) {
            String text = essays.get(i) == null ? "" : essays.get(i);
            List<String> hit = cores.stream().filter(text::contains).toList();
            if (!hit.isEmpty()) {
                out.add(new EssayWarning(i, hit));
            }
        }
        return out;
    }

    static String coreName(String institution) {
        return CORP.matcher(institution == null ? "" : institution).replaceAll(" ").strip().replaceAll("\\s+", " ");
    }

    static Academic academic(Row r) {
        return r.departmentId() == null ? null : new Academic(r.department(), r.grade(), r.semesters(), r.gpa(),
                Boolean.TRUE.equals(r.graduationExpected()));
    }

    static StudentBrief student(Row r) {
        return new StudentBrief(r.applicant().nameKo(), r.department(), r.grade(), r.semesters(), r.gpa(),
                r.graduationExpected());
    }

    static List<CenterPick> centerPicks(List<PickRow> picks, Map<Integer, JobInfo> jobs) {
        return picks.stream().filter(p -> jobs.containsKey(p.jobId())).map(p -> {
            JobInfo j = jobs.get(p.jobId());
            return new CenterPick(p.rank(), p.jobId(), j.title(), j.institution(), p.verdict());
        }).toList();
    }

    /** 낸 지원서의 지망(낼 때 판정). closed는 낸 날 기준이다(낸 뒤 마감돼도 낸 지망은 그대로 유효하다). */
    static List<Pick> snapshotPicks(List<PickRow> picks, Map<Integer, JobInfo> jobs, LocalDate submittedOn) {
        return picks.stream().filter(p -> jobs.containsKey(p.jobId())).map(p -> {
            JobInfo j = jobs.get(p.jobId());
            return new Pick(p.rank(), p.jobId(), j.title(), j.team(), j.institution(), p.verdict(), closed(j, submittedOn));
        }).toList();
    }

    /** 낸 지원서의 '낸 날'(없으면 기준일). */
    static LocalDate submittedOn(Row r, LocalDate fallback) {
        return r.submittedAt() != null ? r.submittedAt().toLocalDate() : fallback;
    }

    static boolean closed(JobInfo j, LocalDate asOf) {
        return j.closesOn() != null && asOf != null && !j.closesOn().isAfter(asOf);
    }

    private static boolean filled(String s) {
        return s != null && !s.isBlank();
    }
}
