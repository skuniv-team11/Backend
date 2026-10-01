package kr.ac.skuniv.coopradar.plan;

import jakarta.validation.Valid;
import kr.ac.skuniv.coopradar.auth.AuthUser;
import kr.ac.skuniv.coopradar.auth.RequireRole;
import kr.ac.skuniv.coopradar.auth.Role;
import kr.ac.skuniv.coopradar.plan.PlanDtos.Plan;
import kr.ac.skuniv.coopradar.plan.PlanDtos.PlanAddRequest;
import kr.ac.skuniv.coopradar.plan.PlanDtos.PlanRanksRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * docs/api #19 담은 직무 · #20 담기 · #21 담기 취소 · #22 1~3지망 순위. 학생만(체험 계정 포함).
 * 담은 목록은 계정에 저장되고 탈퇴하면 같이 지워진다(DB cascade).
 */
@RestController
@RequestMapping("/api/me/plan")
@RequireRole(Role.STUDENT)
public class PlanController {

    private final PlanService plans;

    public PlanController(PlanService plans) {
        this.plans = plans;
    }

    @GetMapping
    public Plan get(AuthUser user) {
        return plans.get(user);
    }

    /** 새로 담으면 201, 이미 담겨 있으면 200(그대로). 본문 없음. */
    @PostMapping("/items")
    public ResponseEntity<Void> add(AuthUser user, @Valid @RequestBody PlanAddRequest body) {
        boolean created = plans.add(user, body.jobId());
        return ResponseEntity.status(created ? HttpStatus.CREATED : HttpStatus.OK).build();
    }

    @DeleteMapping("/items/{jobId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(AuthUser user, @PathVariable long jobId) {
        plans.remove(user, jobId);
    }

    @PutMapping("/ranks")
    public Plan setRanks(AuthUser user, @Valid @RequestBody PlanRanksRequest body) {
        return plans.setRanks(user, body.ranks());
    }
}
