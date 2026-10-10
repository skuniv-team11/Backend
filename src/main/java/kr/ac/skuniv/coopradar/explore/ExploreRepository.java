package kr.ac.skuniv.coopradar.explore;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kr.ac.skuniv.coopradar.common.Times;
import kr.ac.skuniv.coopradar.eligibility.EligibilityDtos.Verdict;
import kr.ac.skuniv.coopradar.explore.ExploreDtos.Fallback;
import kr.ac.skuniv.coopradar.explore.ExploreDtos.Fit;
import kr.ac.skuniv.coopradar.explore.ExploreDtos.Source;
import kr.ac.skuniv.coopradar.explore.ExploreDtos.WhyText;
import kr.ac.skuniv.coopradar.job.InstitutionRef;
import kr.ac.skuniv.coopradar.recommend.RecommendDtos.Blocked;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** 직무 탐색 읽기·쓰기(ADR-0031). 시드 테이블은 읽기만 한다. 학생 글은 로그에 남기지 않는다. */
@Repository
public class ExploreRepository {

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private final JdbcClient db;

    public ExploreRepository(JdbcClient db) {
        this.db = db;
    }

    /** 회차 직무 원문(리스트 순번 순)과 칸별 근거 쪽. 계획서 문서명은 그 직무 근거의 문서. */
    List<JobDoc> jobDocs(int roundId) {
        return db.sql("""
                        SELECT j.id, j.list_seq, j.institution_id, i.name AS institution, j.team, j.title, j.overview,
                               j.education_goal, j.competencies,
                               (SELECT array_agg(p.content ORDER BY p.seq) FROM job_weekly_plan p WHERE p.job_id = j.id) AS weekly,
                               (SELECT e.page FROM field_evidence e WHERE e.job_id = j.id AND e.field_key = 'jobOverview') AS p_overview,
                               (SELECT e.page FROM field_evidence e WHERE e.job_id = j.id AND e.field_key = 'competencies') AS p_comp,
                               (SELECT e.page FROM field_evidence e WHERE e.job_id = j.id AND e.field_key = 'educationGoal') AS p_goal,
                               (SELECT e.page FROM field_evidence e WHERE e.job_id = j.id AND e.field_key = 'majorRequirement') AS p_major,
                               (SELECT d.title FROM field_evidence e JOIN source_document d ON d.id = e.source_document_id
                                WHERE e.job_id = j.id ORDER BY e.id LIMIT 1) AS plan_title
                        FROM job j
                        JOIN institution i ON i.id = j.institution_id
                        WHERE j.round_id = :round
                        ORDER BY j.list_seq""")
                .param("round", roundId)
                .query((rs, n) -> new JobDoc(rs.getInt("id"), rs.getInt("list_seq"),
                        new InstitutionRef(rs.getInt("institution_id"), rs.getString("institution")),
                        rs.getString("team"), rs.getString("title"), rs.getString("overview"),
                        rs.getString("education_goal"), rs.getString("competencies"), strings(rs.getArray("weekly")),
                        page(rs, "p_overview"), page(rs, "p_comp"), page(rs, "p_goal"), page(rs, "p_major"),
                        rs.getString("plan_title")))
                .list();
    }

    /** 저장된 탐색 1회. */
    record Run(long id, int roundId, String termCode, List<String> experiences, List<String> cards, String interestText, Source source,
               Fallback fallback, List<Integer> candidateJobIds, int eligible, int needsCheck, List<Blocked> blockedBy,
               OffsetDateTime createdAt) {
    }

    /** 저장된 탐색 자리. */
    record Stored(int rank, int jobId, Verdict verdict, Fit fit, String studentQuote, String jobQuote,
                  String documentTitle, Integer page, String reason) {
    }

    /** 새로 저장할 탐색. */
    record NewRun(int roundId, List<String> experiences, List<String> cards, String interestText, Source source,
                  Fallback fallback, List<Integer> candidateJobIds, int eligible, int needsCheck,
                  List<Blocked> blockedBy, String model, String promptVersion) {
    }

    record Saved(long id, OffsetDateTime createdAt) {
    }

    Optional<Run> findRun(long userId) {
        return db.sql("""
                        SELECT r.id, r.round_id, rr.term_code, r.experiences, r.card_texts, r.interest_text, r.source,
                               r.fallback_reason, r.candidate_job_ids, r.eligible_count, r.needs_check_count,
                               r.blocked_by::text AS blocked_by, r.created_at
                        FROM explore_run r
                        JOIN recruit_round rr ON rr.id = r.round_id
                        WHERE r.user_id = :user""")
                .param("user", userId)
                .query((rs, n) -> new Run(rs.getLong("id"), rs.getInt("round_id"), rs.getString("term_code"),
                        strings(rs.getArray("experiences")),
                        strings(rs.getArray("card_texts")), rs.getString("interest_text"),
                        Source.valueOf(rs.getString("source")),
                        rs.getString("fallback_reason") == null ? null : Fallback.valueOf(rs.getString("fallback_reason")),
                        integers(rs.getArray("candidate_job_ids")), rs.getInt("eligible_count"),
                        rs.getInt("needs_check_count"),
                        JSON.readValue(rs.getString("blocked_by"), new TypeReference<List<Blocked>>() { }),
                        Times.kst(rs.getObject("created_at", OffsetDateTime.class))))
                .optional();
    }

    List<Stored> items(long runId) {
        return db.sql("""
                        SELECT rank, job_id, verdict, fit, student_quote, job_quote, document_title, page, reason
                        FROM explore_item WHERE run_id = :run ORDER BY rank""")
                .param("run", runId)
                .query((rs, n) -> new Stored(rs.getInt("rank"), rs.getInt("job_id"), Verdict.valueOf(rs.getString("verdict")),
                        Fit.valueOf(rs.getString("fit")), rs.getString("student_quote"), rs.getString("job_quote"),
                        rs.getString("document_title"), page(rs, "page"), rs.getString("reason")))
                .list();
    }

    Map<Integer, WhyText> whys(long runId) {
        Map<Integer, WhyText> out = new HashMap<>();
        db.sql("SELECT job_id, body::text AS body FROM explore_why WHERE run_id = :run")
                .param("run", runId)
                .query(rs -> {
                    out.put(rs.getInt("job_id"), JSON.readValue(rs.getString("body"), WhyText.class));
                });
        return out;
    }

    /**
     * 계정의 탐색을 이번 것으로 바꾼다(계정당 1건). 자리와 함께 만든 '왜 맞나요'도 같이 넣는다.
     *
     * @param whys 직무 id → 설명(자리 순서와 상관없다)
     */
    @Transactional
    public Saved replace(long userId, NewRun run, List<Stored> items, Map<Integer, WhyText> whys) {
        db.sql("DELETE FROM explore_run WHERE user_id = :user").param("user", userId).update();
        Saved saved = db.sql("""
                        INSERT INTO explore_run (user_id, round_id, experiences, card_texts, interest_text, source,
                                                 fallback_reason, candidate_job_ids, eligible_count, needs_check_count,
                                                 blocked_by, model, prompt_version)
                        VALUES (:user, :round, CAST(:experiences AS text[]), CAST(:cards AS text[]), :interest, :source,
                                :fallback, CAST(:candidates AS integer[]), :eligible, :needsCheck,
                                CAST(:blocked AS jsonb), :model, :promptVersion)
                        RETURNING id, created_at""")
                .param("user", userId)
                .param("round", run.roundId())
                .param("experiences", run.experiences().toArray(String[]::new))
                .param("cards", run.cards().toArray(String[]::new))
                .param("interest", run.interestText())
                .param("source", run.source().name())
                .param("fallback", run.fallback() == null ? null : run.fallback().name())
                .param("candidates", run.candidateJobIds().toArray(Integer[]::new))
                .param("eligible", run.eligible())
                .param("needsCheck", run.needsCheck())
                .param("blocked", JSON.writeValueAsString(run.blockedBy()))
                .param("model", run.model())
                .param("promptVersion", run.promptVersion())
                .query((rs, n) -> new Saved(rs.getLong("id"), Times.kst(rs.getObject("created_at", OffsetDateTime.class))))
                .single();
        for (Stored i : items) {
            db.sql("""
                            INSERT INTO explore_item (run_id, rank, job_id, verdict, fit, student_quote, job_quote,
                                                      document_title, page, reason)
                            VALUES (:run, :rank, :job, :verdict, :fit, :sq, :jq, :doc, :page, :reason)""")
                    .param("run", saved.id())
                    .param("rank", i.rank())
                    .param("job", i.jobId())
                    .param("verdict", i.verdict().name())
                    .param("fit", i.fit().name())
                    .param("sq", i.studentQuote())
                    .param("jq", i.jobQuote())
                    .param("doc", i.documentTitle())
                    .param("page", i.page())
                    .param("reason", i.reason())
                    .update();
        }
        new LinkedHashMap<>(whys).forEach((jobId, why) -> insertWhy(saved.id(), jobId, why));
        return saved;
    }

    /** '왜 맞나요' 하나를 넣는다. 같은 직무가 이미 있으면(동시에 두 번 열림) 그대로 둔다. */
    void insertWhy(long runId, int jobId, WhyText why) {
        db.sql("""
                        INSERT INTO explore_why (run_id, job_id, body) VALUES (:run, :job, CAST(:body AS jsonb))
                        ON CONFLICT (run_id, job_id) DO NOTHING""")
                .param("run", runId)
                .param("job", jobId)
                .param("body", JSON.writeValueAsString(why))
                .update();
    }

    /** 계정의 탐색을 지운다(자리·설명은 cascade). 없어도 그대로 끝난다. */
    void delete(long userId) {
        db.sql("DELETE FROM explore_run WHERE user_id = :user").param("user", userId).update();
    }

    private static Integer page(ResultSet rs, String column) throws SQLException {
        int v = rs.getInt(column);
        return rs.wasNull() ? null : v;
    }

    private static List<String> strings(Array array) throws SQLException {
        return array == null ? List.of() : List.of((String[]) array.getArray());
    }

    private static List<Integer> integers(Array array) throws SQLException {
        return array == null ? List.of() : Arrays.asList((Integer[]) array.getArray());
    }
}
