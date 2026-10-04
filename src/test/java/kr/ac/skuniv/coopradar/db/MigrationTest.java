package kr.ac.skuniv.coopradar.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import kr.ac.skuniv.coopradar.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 앱이 뜰 때 Flyway가 V1·V2를 Postgres 18에 적용하는지 본다. */
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
    void 테이블_22개가_만들어진다() {
        Integer tables = jdbc.sql("""
                        SELECT count(*) FROM information_schema.tables
                        WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
                          AND table_name <> 'flyway_schema_history'
                        """)
                .query(Integer.class)
                .single();
        assertThat(tables).isEqualTo(22); // V1 21개 + V3 job_view
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
}
