package kr.ac.skuniv.coopradar.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import kr.ac.skuniv.coopradar.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 앱이 뜰 때 Flyway가 V1~V6을 Postgres 18에 적용하는지 본다. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class MigrationTest {

    @Autowired
    JdbcClient jdbc;

    @Test
    void V1이_적용된다() {
        Boolean success = jdbc.sql("SELECT success FROM flyway_schema_history WHERE version = '1'")
                .query(Boolean.class)
                .single();
        assertThat(success).isTrue();
    }

    @Test
    void 테이블_26개가_만들어진다() {
        Integer tables = jdbc.sql("""
                        SELECT count(*) FROM information_schema.tables
                        WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
                          AND table_name <> 'flyway_schema_history'
                        """)
                .query(Integer.class)
                .single();
        assertThat(tables).isEqualTo(26); // V1 21개 + V3 job_view + V5 certificate + V6 requirement_source + V7 학과 묶음 2개
    }

    @Test
    void V2가_적용되고_좌표_칸이_없다() {
        Boolean success = jdbc.sql("SELECT success FROM flyway_schema_history WHERE version = '2'")
                .query(Boolean.class)
                .single();
        assertThat(success).isTrue();
        // 카카오 결과(좌표)는 저장할 수 없어 칸 자체를 뺐다(ADR-0007)
        List<String> columns = jdbc.sql("""
                        SELECT table_name || '.' || column_name FROM information_schema.columns
                        WHERE table_schema = 'public' AND table_name IN ('area', 'workplace')""")
                .query(String.class)
                .list();
        assertThat(columns).containsExactlyInAnyOrder("area.code", "area.name", "area.sido", "area.sort_order",
                "workplace.address", "workplace.id", "workplace.institution_id");
    }

    @Test
    void V3가_적용되고_신호는_관심_하나_조회_기록_테이블이_있다() {
        Boolean success = jdbc.sql("SELECT success FROM flyway_schema_history WHERE version = '3'")
                .query(Boolean.class)
                .single();
        assertThat(success).isTrue();
        // ADR-0019: 예전 관심 칸은 없애고 지원 의사 칸을 관심으로 바꿨다
        List<String> columns = jdbc.sql("""
                        SELECT table_name || '.' || column_name FROM information_schema.columns
                        WHERE table_schema = 'public' AND table_name IN ('replay_signal', 'job_view')""")
                .query(String.class)
                .list();
        assertThat(columns).containsExactlyInAnyOrder("replay_signal.job_id", "replay_signal.signal_date",
                "replay_signal.interest_count", "job_view.id", "job_view.job_id", "job_view.user_id",
                "job_view.viewed_on");
    }

    @Test
    void V4가_적용되고_수기에_실습_결과_칸이_있다() {
        Boolean success = jdbc.sql("SELECT success FROM flyway_schema_history WHERE version = '4'")
                .query(Boolean.class)
                .single();
        assertThat(success).isTrue();
        // ADR-0020: 실습 결과 중 사실 구절만. 소감 칸은 두지 않는다
        List<String> columns = jdbc.sql("""
                        SELECT column_name FROM information_schema.columns
                        WHERE table_schema = 'public' AND table_name = 'testimonial'""")
                .query(String.class)
                .list();
        assertThat(columns).containsExactlyInAnyOrder("id", "source_document_id", "institution_id", "team_text",
                "activities", "outcomes", "page");
    }

    @Test
    void V5가_적용되고_자격증_코드표와_직무_코드_프로필_자격증_칸이_있다() {
        Boolean success = jdbc.sql("SELECT success FROM flyway_schema_history WHERE version = '5'")
                .query(Boolean.class)
                .single();
        assertThat(success).isTrue();
        // ADR-0021: 자격증 요건이 있으면 코드가 있어야 하고, 없으면 코드도 없다
        Integer mismatched = jdbc.sql("SELECT count(*) FROM job WHERE (certificate = 'NONE') <> (certificate_code IS NULL)")
                .query(Integer.class)
                .single();
        assertThat(mismatched).isZero();
        List<String> columns = jdbc.sql("""
                        SELECT table_name || '.' || column_name FROM information_schema.columns
                        WHERE table_schema = 'public' AND (table_name = 'certificate'
                           OR column_name IN ('certificate_code', 'certificates'))""")
                .query(String.class)
                .list();
        assertThat(columns).containsExactlyInAnyOrder("certificate.code", "certificate.label", "certificate.sort_order",
                "job.certificate_code", "student_profile.certificates");
    }

    @Test
    void V6이_적용되고_판정_출처는_직무마다_학년이_있고_리스트_출처에는_쪽이_없다() {
        Boolean success = jdbc.sql("SELECT success FROM flyway_schema_history WHERE version = '6'")
                .query(Boolean.class)
                .single();
        assertThat(success).isTrue();
        // ADR-0023: 학년은 40직무 모두 리스트 칸, 학점 하한·자격증은 있는 직무만
        Integer missing = jdbc.sql("""
                        SELECT count(*) FROM job j
                        WHERE NOT EXISTS (SELECT 1 FROM requirement_source s WHERE s.job_id = j.id AND s.item = 'GRADE')
                           OR (j.gpa_min IS NOT NULL
                               AND NOT EXISTS (SELECT 1 FROM requirement_source s WHERE s.job_id = j.id AND s.item = 'GPA'))
                           OR (j.certificate <> 'NONE'
                               AND NOT EXISTS (SELECT 1 FROM requirement_source s WHERE s.job_id = j.id AND s.item = 'CERTIFICATE'))""")
                .query(Integer.class)
                .single();
        assertThat(missing).isZero();
        Integer listWithPage = jdbc.sql(
                        "SELECT count(*) FROM requirement_source WHERE source_type = 'INSTITUTION_LIST' AND page IS NOT NULL")
                .query(Integer.class)
                .single();
        assertThat(listWithPage).isZero();
    }
}
