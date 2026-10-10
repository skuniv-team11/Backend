package kr.ac.skuniv.coopradar.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import kr.ac.skuniv.coopradar.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Flyway가 V1 다음에 R__seed.sql(ADR-0014)을 적용하고, 시드가 2026-2 실제 자료와 리플레이 규칙(ADR-0009)을 지키는지 본다.
 * 숫자는 참여기관 리스트·매칭 결과와 같아야 한다: 18기관 · 40직무 · 정원 59 · 배정 21 · 수기 17.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SeedTest {

    @Autowired
    JdbcClient db;

    private long count(String sql) {
        return db.sql(sql).query(Long.class).single();
    }

    @Test
    void 반복_마이그레이션으로_시드가_적용된다() {
        Boolean ok = db.sql("SELECT success FROM flyway_schema_history WHERE script = 'R__seed.sql'")
                .query(Boolean.class).single();
        assertThat(ok).isTrue();
    }

    @Test
    void 기관_직무_정원_배정_수기_수가_원본과_같다() {
        Map<String, Object> n = db.sql("""
                SELECT (SELECT count(*) FROM institution)            AS institutions,
                       (SELECT count(*) FROM job)                    AS jobs,
                       (SELECT sum(headcount) FROM job)              AS seats,
                       (SELECT sum(final_assigned) FROM job)         AS assigned,
                       (SELECT count(*) FROM testimonial)            AS testimonials,
                       (SELECT count(*) FROM recruit_round WHERE term_code = '2026-2') AS rounds
                """).query().singleRow();
        assertThat(n).containsEntry("institutions", 18L).containsEntry("jobs", 40L)
                .containsEntry("seats", 59L).containsEntry("assigned", 21L)
                .containsEntry("testimonials", 17L).containsEntry("rounds", 1L);
    }

    @Test
    void 소개서_사진과_검토_알림_수가_시드와_같다() {
        // ADR-0030: 사진 칸이 있는 소개서 14곳 55장, 표기 차이·오타·사람 판단 6건을 뺀 검토 알림 6건
        assertThat(count("SELECT count(*) FROM institution_photo")).isEqualTo(55);
        assertThat(count("SELECT count(DISTINCT institution_id) FROM institution_photo")).isEqualTo(14);
        assertThat(count("SELECT count(*) FROM source_document WHERE kind = 'INTRODUCTION'")).isEqualTo(14);
        assertThat(count("SELECT count(*) FROM review_alert")).isEqualTo(6);
        // 수기는 전문이 들어 있다(실습 결과·소감)
        assertThat(count("SELECT count(*) FROM testimonial WHERE results IS NULL OR reflection IS NULL")).isZero();
        // 학과·학년은 넣지 않는다 — 학기·기관·팀과 같이 보이면 선배를 알아볼 수 있다(ADR-0037)
        assertThat(count("SELECT count(*) FROM testimonial WHERE major_text IS NOT NULL OR grade_text IS NOT NULL"))
                .isZero();
    }

    @Test
    void 예시_프로필_학과가_있고_학과는_모두_재학생이_있다() {
        assertThat(count("SELECT count(*) FROM department WHERE name = '메이크업디자인학과'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM department WHERE enrolled_count = 0")).isZero();
    }

    @Test
    void 날짜_없는_센터_모집마감은_회차_안의_가상_마감일이다() {
        assertThat(count("""
                SELECT count(*) FROM job j JOIN recruit_round r ON r.id = j.round_id
                WHERE j.closes_on_is_virtual AND (j.closes_on IS NULL OR j.closes_on <= r.recruit_start
                                                  OR j.closes_on >= r.recruit_end)
                """)).isZero();
    }

    @Test
    void 마감일은_회차_종료일보다_이를_때만_있고_가상_마감은_센터_모집마감뿐이다() {
        assertThat(count("""
                SELECT count(*) FROM job j JOIN recruit_round r ON r.id = j.round_id
                WHERE j.closes_on > r.recruit_end
                """)).isZero();
        assertThat(count("SELECT count(*) FROM job WHERE closes_on_is_virtual AND close_reason <> 'CENTER_CLOSED'")).isZero();
    }

    @Test
    void 직무마다_근거가_있고_근거_쪽은_문서_안이다() {
        assertThat(count("SELECT count(*) FROM job j WHERE NOT EXISTS (SELECT 1 FROM field_evidence e WHERE e.job_id = j.id)"))
                .isZero();
        assertThat(count("""
                SELECT count(*) FROM field_evidence e JOIN source_document d ON d.id = e.source_document_id
                WHERE e.page > d.page_count
                """)).isZero();
        assertThat(count("""
                SELECT count(*) FROM testimonial t JOIN source_document d ON d.id = t.source_document_id
                WHERE t.page > d.page_count OR d.kind <> 'TESTIMONIAL'
                """)).isZero();
    }

    @Test
    void 수기_실습_결과는_사실_구절만_원문_60자_이내로_들어_있다() {
        // ADR-0020: 2026-2 시드는 17건 중 13건에 구절이 있고 합 24개. 감상·진로 낱말·1인칭은 없다
        assertThat(count("SELECT count(*) FROM testimonial WHERE cardinality(outcomes) > 0")).isEqualTo(13);
        assertThat(count("SELECT coalesce(sum(cardinality(outcomes)), 0) FROM testimonial")).isEqualTo(24);
        assertThat(count("""
                SELECT count(*) FROM testimonial t, unnest(t.outcomes) o
                WHERE char_length(o) > 60 OR o ~ '(뿌듯|보람|느꼈|배울 수|입사|채용|정직원|정규직|합격|저의|제가)'
                """)).isZero();
    }

    @Test
    void 사는_곳은_서울_인천_경기_시군구_83곳이고_좌표는_없다() {
        // 행정표준코드 법정동코드 전체자료(2026-10-01) → pipeline/seed/areas.py. 2026-07-01 인천 개편이 반영돼 있다
        assertThat(count("SELECT count(*) FROM area")).isEqualTo(83);
        assertThat(count("SELECT count(*) FROM area WHERE sido = '서울'")).isEqualTo(25);
        assertThat(count("SELECT count(*) FROM area WHERE sido = '인천'")).isEqualTo(11);
        assertThat(count("SELECT count(*) FROM area WHERE code IN ('28125', '28155', '28275', '28290')")).isEqualTo(4);
        assertThat(count("SELECT count(*) FROM area WHERE code IN ('28110', '28140', '28260')")).isZero(); // 폐지된 중구·동구·서구
        assertThat(count("SELECT count(*) FROM area WHERE code = '41110'")).isZero(); // 일반구가 있는 시는 구만
        assertThat(count("SELECT count(*) FROM area WHERE code = '11350' AND name = '노원구'")).isEqualTo(1); // 예시 프로필
    }

    @Test
    void 리스트의_선호_전공_표기_25종이_직무에_연결된다() {
        // 표기 → 학과 연결은 사람이 확정한 것만 들어간다(pipeline/seed/curated/major_aliases.csv의 EXACT·CONFIRMED)
        assertThat(count("SELECT count(*) FROM major_alias")).isEqualTo(25);
        assertThat(count("SELECT count(DISTINCT alias_id) FROM job_major_alias")).isEqualTo(25);
        assertThat(count("SELECT count(*) FROM job WHERE major_text IS NOT NULL AND id NOT IN (SELECT job_id FROM job_major_alias)"))
                .isZero();
    }

    @Test
    void 가까운_학과_묶음은_확정한_것만_들어간다() {
        // ADR-0028: pipeline/seed/curated/department_clusters.csv의 CONFIRMED 11묶음. 한 묶음에 학과 둘 이상
        assertThat(count("SELECT count(*) FROM department_cluster")).isEqualTo(11);
        assertThat(count("SELECT count(*) FROM (SELECT cluster_id FROM department_cluster_member GROUP BY cluster_id "
                + "HAVING count(*) < 2) t")).isZero();
        // 컴퓨터공학과(26)와 소프트웨어학과(28)는 같은 묶음, 군사학과(15)는 어느 묶음에도 없다
        assertThat(count("SELECT count(*) FROM department_cluster_member a JOIN department_cluster_member b "
                + "ON a.cluster_id = b.cluster_id WHERE a.department_id = 26 AND b.department_id = 28")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM department_cluster_member WHERE department_id = 15")).isZero();
    }
}
