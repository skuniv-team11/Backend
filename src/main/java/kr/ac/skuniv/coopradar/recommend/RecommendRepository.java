package kr.ac.skuniv.coopradar.recommend;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kr.ac.skuniv.coopradar.job.Stipend;
import kr.ac.skuniv.coopradar.recommend.FitScorer.Features;
import kr.ac.skuniv.coopradar.recommend.RecommendDtos.Citation;
import kr.ac.skuniv.coopradar.recommend.RecommendDtos.SourceType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 추천에 쓰는 시드 읽기(직무 텍스트·유형·지원비, 근거 인용). 시드 테이블은 읽기만 한다(V1 원칙). */
@Repository
public class RecommendRepository {

    private final JdbcClient db;

    public RecommendRepository(JdbcClient db) {
        this.db = db;
    }

    /**
     * 회차 직무별 점수 재료. 키워드 유사도 텍스트 = 부서 · 직무명 · 직무 개요 · 교육 목표 · 요구 역량 · 주차 계획 · 기관 업태·종목.
     * 선호 전공 원문은 넣지 않는다(전공은 규칙이 따로 본다, E5와 같은 이유).
     */
    Map<Integer, Features> features(int roundId) {
        Map<Integer, Features> out = new HashMap<>();
        db.sql("""
                        SELECT j.id, j.job_type, j.stipend_basis, j.stipend_amount,
                               concat_ws(E'\\n', j.team, j.title, j.overview, j.education_goal, j.competencies,
                                         (SELECT string_agg(p.content, E'\\n' ORDER BY p.seq)
                                          FROM job_weekly_plan p WHERE p.job_id = j.id),
                                         i.business_type, i.business_item) AS text
                        FROM job j
                        JOIN institution i ON i.id = j.institution_id
                        WHERE j.round_id = :round""")
                .param("round", roundId)
                .query(rs -> {
                    out.put(rs.getInt("id"), new Features(rs.getString("text"), rs.getString("job_type"),
                            Stipend.of(rs.getString("stipend_basis"), (Integer) rs.getObject("stipend_amount"))));
                });
        return out;
    }

    /** 같은 기관의 가장 최근 선배 수기 1건 — 첫 번째 실습 내용을 인용한다. */
    Optional<Citation> testimonialCitation(int institutionId) {
        return db.sql("""
                        SELECT d.title, t.page, t.activities[1] AS quote
                        FROM testimonial t
                        JOIN source_document d ON d.id = t.source_document_id
                        WHERE t.institution_id = :institution
                        ORDER BY d.term_code DESC, t.page, t.id
                        LIMIT 1""")
                .param("institution", institutionId)
                .query((rs, n) -> new Citation(SourceType.TESTIMONIAL, rs.getString("title"), rs.getInt("page"),
                        rs.getString("quote")))
                .optional();
    }

    /** 운영계획서의 직무 개요 근거(없으면 교육 목표). */
    Optional<Citation> planCitation(int jobId) {
        List<Citation> rows = db.sql("""
                        SELECT d.title, e.page, e.quote
                        FROM field_evidence e
                        JOIN source_document d ON d.id = e.source_document_id
                        WHERE e.job_id = :job AND e.field_key IN ('jobOverview', 'educationGoal')
                        ORDER BY CASE e.field_key WHEN 'jobOverview' THEN 0 ELSE 1 END""")
                .param("job", jobId)
                .query((rs, n) -> new Citation(SourceType.OPERATION_PLAN, rs.getString("title"), rs.getInt("page"),
                        rs.getString("quote")))
                .list();
        return rows.stream().findFirst();
    }

    /** 이유 문장 프롬프트에 넣는 직무 사실. 원문 그대로(검증에서 인용 대조에도 쓴다). */
    record JobFacts(String institution, String title, String team, String jobType, String overview,
                    String competencies, String educationGoal, String majorText) {
    }

    Optional<JobFacts> facts(int jobId) {
        return db.sql("""
                        SELECT i.name AS institution, j.title, j.team, j.job_type, j.overview, j.competencies,
                               j.education_goal, j.major_text
                        FROM job j JOIN institution i ON i.id = j.institution_id
                        WHERE j.id = :job""")
                .param("job", jobId)
                .query((rs, n) -> new JobFacts(rs.getString("institution"), rs.getString("title"), rs.getString("team"),
                        rs.getString("job_type"), rs.getString("overview"), rs.getString("competencies"),
                        rs.getString("education_goal"), rs.getString("major_text")))
                .optional();
    }
}
