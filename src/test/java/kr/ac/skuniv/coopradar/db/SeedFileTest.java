package kr.ac.skuniv.coopradar.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/**
 * R__seed.sql이 pipeline/seed/seed.json·ncs.json에서 to_sql.py로 만든 그대로인지 본다(ADR-0014·0032).
 * 머리글의 세 해시: seed.json 전체, ncs.json 전체, 그리고 머리글 아래 본문. 손으로 고치거나 seed.json만 바꾸면 여기서 걸린다.
 * 고치는 법: python pipeline/seed/to_sql.py 로 다시 만들어 같이 커밋한다.
 */
class SeedFileTest {

    private static final Path SQL = Path.of("src/main/resources/db/migration/R__seed.sql");
    private static final Path SEED = Path.of("pipeline/seed/seed.json");
    private static final Path NCS = Path.of("pipeline/seed/ncs.json");

    private static String lf(Path p) throws Exception {
        return Files.readString(p, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private static String sha256(String s) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void 머리글의_해시가_seed_json_ncs_json과_본문에_맞는다() throws Exception {
        String sql = lf(SQL);
        String[] lines = sql.split("\n", 6);
        assertThat(lines[2]).isEqualTo("-- seed.json sha256: " + sha256(lf(SEED)));
        assertThat(lines[3]).isEqualTo("-- ncs.json sha256: " + sha256(lf(NCS)));
        String body = lines[5].substring(lines[5].indexOf('\n') + 1);   // 머리글 5줄 + 빈 줄 다음부터
        assertThat(lines[4]).isEqualTo("-- body sha256: " + sha256(body));
    }
}
