package kr.ac.skuniv.coopradar.db;

import static org.assertj.core.api.Assertions.assertThat;

import kr.ac.skuniv.coopradar.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 앱이 뜰 때 Flyway가 V1을 Postgres 18에 적용하는지 본다. */
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
    void 테이블_21개가_만들어진다() {
        Integer tables = jdbc.sql("""
                        SELECT count(*) FROM information_schema.tables
                        WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
                          AND table_name <> 'flyway_schema_history'
                        """)
                .query(Integer.class)
                .single();
        assertThat(tables).isEqualTo(21);
    }
}
