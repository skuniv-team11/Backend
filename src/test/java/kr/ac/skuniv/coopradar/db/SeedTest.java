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
    void 예시_프로필_학과가_있고_학과는_모두_재학생이_있다() {
        assertThat(count("SELECT count(*) FROM department WHERE name = '메이크업디자인학과'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM department WHERE enrolled_count = 0")).isZero();
    }

    @Test
    void 리플레이_지원_의사_합은_배정_수이고_마감일부터와_회차_밖에는_신호가_없다() {
        assertThat(count("""
                SELECT count(*) FROM job j
                WHERE j.final_assigned <> (SELECT coalesce(sum(intent_count), 0) FROM replay_signal s WHERE s.job_id = j.id)
                """)).isZero();
        assertThat(count("""
                SELECT count(*) FROM replay_signal s JOIN job j ON j.id = s.job_id JOIN recruit_round r ON r.id = j.round_id
                WHERE s.signal_date >= j.closes_on OR s.signal_date < r.recruit_start OR s.signal_date > r.recruit_end
                """)).isZero();
        // 관심 누적은 어느 날이든 지원 의사 누적 이상
        assertThat(count("""
                SELECT count(*) FROM (
                  SELECT sum(interest_count) OVER w AS interest, sum(intent_count) OVER w AS intent
                  FROM replay_signal WINDOW w AS (PARTITION BY job_id ORDER BY signal_date)) t
                WHERE interest < intent
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
}
