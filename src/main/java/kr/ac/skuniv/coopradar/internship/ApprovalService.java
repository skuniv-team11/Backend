package kr.ac.skuniv.coopradar.internship;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import kr.ac.skuniv.coopradar.common.ApiException;
import kr.ac.skuniv.coopradar.common.ErrorCode;
import kr.ac.skuniv.coopradar.internship.ApplicationRepository.ApprovalRow;
import kr.ac.skuniv.coopradar.internship.ApplicationRepository.JobInfo;
import kr.ac.skuniv.coopradar.internship.ApplicationRepository.Row;
import kr.ac.skuniv.coopradar.internship.ApplicationViews.Basis;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.Academic;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.ApprovalKind;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.ApprovalPick;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.ApprovalPlacement;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.ApprovalStatus;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.ApprovalStudent;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.ApprovalView;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.Status;
import kr.ac.skuniv.coopradar.job.RoundRef;
import kr.ac.skuniv.coopradar.reference.RoundService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 학과(부)장 승인 링크(#42·#43, 공개, ADR-0033). 링크의 토큰이 곧 권한이다(128비트 무작위). 메일은 보내지 않고 학생(지원서)·
 * 센터(학점 인정)가 학과 사무실에 링크를 전한다. 보여 주는 것은 승인에 필요한 것만 — 이름·학번·학과·학년·지망(또는 실습 자리).
 * 연락처·주소·자기소개서는 보여 주지 않는다. 지원서 링크는 매칭이 확정되면 404로 닫는다.
 */
@Service
public class ApprovalService {

    private final ApplicationRepository repo;
    private final ApplicationService applications;
    private final RoundService rounds;
    private final Clock clock;

    public ApprovalService(ApplicationRepository repo, ApplicationService applications, RoundService rounds, Clock clock) {
        this.repo = repo;
        this.applications = applications;
        this.rounds = rounds;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ApprovalView get(String token) {
        return view(find(token));
    }

    @Transactional
    public ApprovalView approve(String token) {
        Found f = find(token);
        if (status(f) == ApprovalStatus.STALE) {
            throw new ApiException(ErrorCode.STATE_CONFLICT, "승인을 요청한 뒤 지원서가 바뀌었어요. 학생에게 새 링크를 받아 주세요");
        }
        repo.approve(token, clock.instant());
        return view(find(token));
    }

    private record Found(ApprovalRow approval, Row application, Basis basis) {
    }

    /** 지원서 승인 링크는 매칭이 확정되면 닫는다(링크로 이름·학번이 계속 열리지 않게). */
    private Found find(String token) {
        if (token == null || !token.matches("^[0-9a-f]{32}$")) {
            throw new ApiException(ErrorCode.APPROVAL_NOT_FOUND, "승인 링크가 맞지 않아요");
        }
        ApprovalRow a = repo.approvalByToken(token)
                .orElseThrow(() -> new ApiException(ErrorCode.APPROVAL_NOT_FOUND, "승인 링크가 맞지 않거나 새 링크로 바뀌었어요"));
        Row r = repo.byId(a.applicationId()).orElseThrow();
        if (a.kind() == ApprovalKind.APPLICATION && r.status() == Status.MATCHED) {
            throw new ApiException(ErrorCode.APPROVAL_NOT_FOUND, "매칭이 끝나 승인 링크를 닫았어요");
        }
        return new Found(a, r, applications.basis(r));
    }

    private static ApprovalStatus status(Found f) {
        return f.approval().kind() == ApprovalKind.APPLICATION
                ? ApplicationViews.approvalStatus(f.approval(), ApplicationViews.contentHash(f.application(), f.basis()))
                : ApplicationViews.approvalStatus(f.approval(), null);
    }

    private ApprovalView view(Found f) {
        Row r = f.application();
        var round = rounds.current();
        // 학과·학년은 해시와 같은 값(고칠 수 있으면 저장한 프로필, 낸 뒤면 고정한 학적)
        Academic academic = f.basis().academic();
        ApprovalStudent student = new ApprovalStudent(r.applicant().nameKo(), r.applicant().studentNo(),
                academic == null ? null : academic.department(), academic == null ? null : academic.grade());
        Map<Integer, JobInfo> jobs = repo.jobs(f.basis().jobIds());
        List<ApprovalPick> picks = List.of();
        ApprovalPlacement placement = null;
        if (f.approval().kind() == ApprovalKind.APPLICATION) {
            picks = f.basis().picks().stream().filter(p -> jobs.containsKey(p.jobId()))
                    .map(p -> new ApprovalPick(p.rank(), jobs.get(p.jobId()).title(), jobs.get(p.jobId()).institution()))
                    .toList();
        } else if (r.matchedRank() != null) {
            JobInfo j = f.basis().picks().stream().filter(p -> p.rank() == r.matchedRank()).findFirst()
                    .map(p -> jobs.get(p.jobId())).orElse(null);
            if (j != null) {
                placement = new ApprovalPlacement(j.title(), j.team(), j.institution(), j.periodStart(), j.periodEnd());
            }
        }
        return new ApprovalView(f.approval().kind(), status(f), f.approval().requestedAt(), f.approval().approvedAt(),
                student, new RoundRef(round.id(), round.termCode()), picks, placement);
    }
}
