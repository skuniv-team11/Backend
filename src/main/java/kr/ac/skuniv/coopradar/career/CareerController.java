package kr.ac.skuniv.coopradar.career;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import kr.ac.skuniv.coopradar.auth.AuthUser;
import kr.ac.skuniv.coopradar.auth.ClientIp;
import kr.ac.skuniv.coopradar.auth.RequireRole;
import kr.ac.skuniv.coopradar.auth.Role;
import kr.ac.skuniv.coopradar.career.CareerDtos.JobCareer;
import kr.ac.skuniv.coopradar.career.CareerDtos.Report;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * docs/api #33 직무의 커리어 길(로그인) · #34~#36 커리어 리포트(학생, ADR-0032). AI가 실패하거나 한도에 걸려도
 * 200 + 능력단위 목록(source NONE)이다.
 */
@RestController
public class CareerController {

    private final CareerService career;
    private final ClientIp clientIp;

    public CareerController(CareerService career, ClientIp clientIp) {
        this.career = career;
        this.clientIp = clientIp;
    }

    @GetMapping("/api/jobs/{jobId}/career")
    public JobCareer path(@PathVariable int jobId) {
        return career.path(jobId);
    }

    @PostMapping("/api/me/career-report")
    @RequireRole(Role.STUDENT)
    public Report create(@Valid @RequestBody CareerReportRequest body, AuthUser user, HttpServletRequest request) {
        return career.create(user, clientIp.of(request), body);
    }

    @GetMapping("/api/me/career-report")
    @RequireRole(Role.STUDENT)
    public Report mine(AuthUser user) {
        return career.mine(user);
    }

    @DeleteMapping("/api/me/career-report")
    @RequireRole(Role.STUDENT)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(AuthUser user) {
        career.delete(user);
    }
}
