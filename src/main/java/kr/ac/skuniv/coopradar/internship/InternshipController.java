package kr.ac.skuniv.coopradar.internship;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import kr.ac.skuniv.coopradar.auth.AuthUser;
import kr.ac.skuniv.coopradar.auth.PublicApi;
import kr.ac.skuniv.coopradar.auth.RequireRole;
import kr.ac.skuniv.coopradar.auth.Role;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.Advanced;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.Application;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.ApprovalView;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.CloseBoard;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.DemoStep;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.Inbox;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.Internship;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.InterviewMode;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.PlacementBoard;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.Reminded;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.Result;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.Status;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.StudentDocument;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * docs/api #37~#58 현장실습 진행(ADR-0033): 지원서(학생) · 학과(부)장 승인(공개, 링크 토큰) · 내 현장실습(학생) ·
 * 접수함·매칭·선발·마무리(센터) · 시연 버튼(체험 센터).
 */
@RestController
public class InternshipController {

    private final ApplicationService applications;
    private final ApprovalService approvals;
    private final TimelineService timeline;
    private final CenterFlowService center;
    private final DemoService demo;

    public InternshipController(ApplicationService applications, ApprovalService approvals, TimelineService timeline,
                                CenterFlowService center, DemoService demo) {
        this.applications = applications;
        this.approvals = approvals;
        this.timeline = timeline;
        this.center = center;
        this.demo = demo;
    }

    // ───────────── 학생: 지원서 ─────────────

    @GetMapping("/api/me/application")
    @RequireRole(Role.STUDENT)
    public Application application(AuthUser user) {
        return applications.get(user);
    }

    @PutMapping("/api/me/application")
    @RequireRole(Role.STUDENT)
    public Application save(@Valid @RequestBody ApplicationRequest body, AuthUser user) {
        return applications.save(user, body);
    }

    @DeleteMapping("/api/me/application")
    @RequireRole(Role.STUDENT)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteApplication(AuthUser user) {
        applications.delete(user);
    }

    @PostMapping("/api/me/application/approval")
    @RequireRole(Role.STUDENT)
    public Application requestApproval(AuthUser user) {
        return applications.requestApproval(user);
    }

    @PostMapping("/api/me/application/submit")
    @RequireRole(Role.STUDENT)
    public Application submit(AuthUser user) {
        return applications.submit(user);
    }

    // ───────────── 공개: 학과(부)장 승인 ─────────────

    @GetMapping("/api/approvals/{token}")
    @PublicApi
    public ApprovalView approval(@PathVariable String token) {
        return approvals.get(token);
    }

    @PostMapping("/api/approvals/{token}")
    @PublicApi
    public ApprovalView approve(@PathVariable String token) {
        return approvals.approve(token);
    }

    // ───────────── 학생: 내 현장실습 ─────────────

    @GetMapping("/api/me/internship")
    @RequireRole(Role.STUDENT)
    public Internship internship(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
                                 AuthUser user) {
        return timeline.timeline(user, asOf);
    }

    @PutMapping("/api/me/internship/documents/{kind}")
    @RequireRole(Role.STUDENT)
    public Internship submitDocument(@PathVariable StudentDocument kind, AuthUser user) {
        return timeline.submitDocument(user, kind);
    }

    // ───────────── 센터 ─────────────

    public record StatusRequest(Status status, @Size(max = 300, message = "300자 이하") String reason) {
    }

    public record MatchRequest(Integer rank) {
    }

    public record SelectionRequest(OffsetDateTime interviewAt, InterviewMode interviewMode, Result result) {
    }

    public record DocumentsRequest(Boolean evaluation, Boolean attendance) {
    }

    public record AdvanceRequest(DemoStep to) {
    }

    @GetMapping("/api/center/applications")
    @RequireRole(Role.CENTER)
    public Inbox inbox(AuthUser user) {
        return center.inbox(user);
    }

    @GetMapping("/api/center/applications/{applicationId}")
    @RequireRole(Role.CENTER)
    public Application detail(@PathVariable long applicationId, AuthUser user) {
        return center.detail(user, applicationId);
    }

    @PutMapping("/api/center/applications/{applicationId}/status")
    @RequireRole(Role.CENTER)
    public Application setStatus(@PathVariable long applicationId, @Valid @RequestBody StatusRequest body, AuthUser user) {
        return center.setStatus(user, applicationId, body.status(), body.reason());
    }

    @GetMapping("/api/center/placement")
    @RequireRole(Role.CENTER)
    public PlacementBoard placement(AuthUser user) {
        return center.placement(user);
    }

    @PutMapping("/api/center/applications/{applicationId}/match")
    @RequireRole(Role.CENTER)
    public PlacementBoard match(@PathVariable long applicationId, @RequestBody MatchRequest body, AuthUser user) {
        return center.match(user, applicationId, body.rank());
    }

    @PostMapping("/api/center/placement/confirm")
    @RequireRole(Role.CENTER)
    public PlacementBoard confirm(AuthUser user) {
        return center.confirm(user);
    }

    @PutMapping("/api/center/applications/{applicationId}/selection")
    @RequireRole(Role.CENTER)
    public PlacementBoard select(@PathVariable long applicationId, @RequestBody SelectionRequest body, AuthUser user) {
        return center.select(user, applicationId, body.interviewAt(), body.interviewMode(), body.result());
    }

    @PostMapping("/api/center/placement/notify")
    @RequireRole(Role.CENTER)
    public PlacementBoard notifyResults(AuthUser user) {
        return center.notifyResults(user);
    }

    @GetMapping("/api/center/close")
    @RequireRole(Role.CENTER)
    public CloseBoard close(AuthUser user) {
        return center.close(user);
    }

    @PutMapping("/api/center/close/{applicationId}")
    @RequireRole(Role.CENTER)
    public CloseBoard institutionDocuments(@PathVariable long applicationId, @RequestBody DocumentsRequest body,
                                           AuthUser user) {
        return center.institutionDocuments(user, applicationId, body.evaluation(), body.attendance());
    }

    @PostMapping("/api/center/close/remind")
    @RequireRole(Role.CENTER)
    public Reminded remind(AuthUser user) {
        return center.remind(user);
    }

    @GetMapping("/api/center/close/credits.csv")
    @RequireRole(Role.CENTER)
    public ResponseEntity<byte[]> credits(AuthUser user) {
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"credits.csv\"")
                .body(center.creditsCsv(user));
    }

    @PostMapping("/api/center/demo/advance")
    @RequireRole(Role.CENTER)
    public Advanced advance(@RequestBody AdvanceRequest body, AuthUser user) {
        if (body.to() == null) {
            throw kr.ac.skuniv.coopradar.common.ApiException.invalid("to", "RECEIVED·MATCHED·SELECTED·CLOSING");
        }
        return demo.advance(user, body.to());
    }
}
