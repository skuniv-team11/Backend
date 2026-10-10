package kr.ac.skuniv.coopradar.job;

import java.time.LocalDate;
import kr.ac.skuniv.coopradar.auth.AuthUser;
import kr.ac.skuniv.coopradar.auth.Role;
import kr.ac.skuniv.coopradar.common.ApiException;
import kr.ac.skuniv.coopradar.common.ErrorCode;
import kr.ac.skuniv.coopradar.reference.ReferenceDates;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 직무 상세 조회수(ADR-0019). 학생이 직무 상세(#17)를 열면 계정마다 직무별로 하루(한국 시간) 한 번만 센다.
 * 센터 담당자가 연 것은 세지 않는다. 실제 값만 둔다(가상 조회수 없음). 조회수는 #25로 따로 준다.
 */
@Service
public class JobViewService {

    private static final Logger log = LoggerFactory.getLogger(JobViewService.class);

    private final JdbcClient db;
    private final ReferenceDates dates;

    public JobViewService(JdbcClient db, ReferenceDates dates) {
        this.db = db;
        this.dates = dates;
    }

    /**
     * 조회 한 번을 기록한다. 기록이 실패해도 직무 상세는 그대로 보여 준다(로그에 직무 id만 남긴다).
     * 문장 하나라 트랜잭션 없이(자동 커밋) 둔다 — 실패를 삼켜도 커밋에서 다시 터지지 않게.
     */
    public void record(long jobId, AuthUser user) {
        if (user.role() != Role.STUDENT) {
            return;
        }
        try {
            db.sql("""
                            INSERT INTO job_view (job_id, user_id, viewed_on) VALUES (:job, :user, :day)
                            ON CONFLICT ON CONSTRAINT job_view_once_a_day DO NOTHING""")
                    .param("job", jobId)
                    .param("user", user.id())
                    .param("day", today())
                    .update();
        } catch (RuntimeException e) {
            log.warn("조회수 기록 실패 job={}: {}", jobId, e.getClass().getSimpleName());
        }
    }

    /** docs/api #25. 없는 직무는 404 JOB_NOT_FOUND. */
    @Transactional(readOnly = true)
    public JobViews views(long jobId) {
        boolean exists = db.sql("SELECT EXISTS (SELECT 1 FROM job WHERE id = :job)")
                .param("job", jobId).query(Boolean.class).single();
        if (!exists) {
            throw new ApiException(ErrorCode.JOB_NOT_FOUND, "없는 직무예요");
        }
        return db.sql("""
                        SELECT count(*) AS views, count(*) FILTER (WHERE viewed_on = :day) AS today
                        FROM job_view WHERE job_id = :job""")
                .param("job", jobId)
                .param("day", today())
                .query((rs, n) -> new JobViews((int) jobId, rs.getInt("views"), rs.getInt("today")))
                .single();
    }

    private LocalDate today() {
        return dates.today();
    }

    /**
     * @param views      학생 계정마다 직무별로 하루 한 번 센 조회 수의 합(지금까지)
     * @param todayViews 그중 오늘(한국 시간) 조회 수
     */
    public record JobViews(int jobId, int views, int todayViews) {
    }
}
