package kr.ac.skuniv.coopradar.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/** 직무 상세의 DB 없는 부분: 근거 표기 ↔ V1 허용 필드, 최저임금 대비 %. */
class JobUnitTest {

    @Test
    void 근거_표기는_V1_허용_필드와_같은_집합이고_모두_한글_표기가_있다() throws IOException {
        String ddl = new ClassPathResource("db/migration/V1__init.sql").getContentAsString(StandardCharsets.UTF_8);
        Matcher block = Pattern.compile("CONSTRAINT field_evidence_allowed_key CHECK \\((.*?)\\n\\s*\\)\\n\\);",
                Pattern.DOTALL).matcher(ddl);
        assertThat(block.find()).isTrue();
        Matcher institution = Pattern.compile("institution_id IS NOT NULL AND field_key IN \\((.*?)\\)\\)", Pattern.DOTALL)
                .matcher(block.group(1));
        Matcher job = Pattern.compile("job_id IS NOT NULL AND field_key IN \\((.*?)\\)\\)", Pattern.DOTALL)
                .matcher(block.group(1));
        assertThat(institution.find()).isTrue();
        assertThat(job.find()).isTrue();

        assertThat(EvidenceLabels.INSTITUTION.keySet()).containsExactlyElementsOf(quoted(institution.group(1)));
        assertThat(EvidenceLabels.JOB.keySet()).containsExactlyElementsOf(quoted(job.group(1)));
        assertThat(EvidenceLabels.label("stipendAmount")).isEqualTo("실습지원비"); // 계약 예시 표기
        assertThat(EvidenceLabels.label("gpaRequirement")).isEqualTo("학점 요건");
        assertThat(EvidenceLabels.order("department")).isLessThan(EvidenceLabels.order("name"));
    }

    @Test
    void 최저임금_대비_퍼센트는_소수_첫째_자리_반올림이고_기준이나_금액이_없으면_null() {
        assertThat(Stipend.of("MONTHLY", 2_156_880).minWageRatio()).isEqualByComparingTo("100.0");
        assertThat(Stipend.of("HOURLY", 10_320).minWageRatio()).isEqualByComparingTo("100.0");
        assertThat(Stipend.of("MONTHLY", 1_618_000).minWageRatio()).isEqualByComparingTo("75.0");
        assertThat(Stipend.of("MONTHLY", 1_617_660).minWageRatio()).isEqualByComparingTo("75.0");
        assertThat(Stipend.of("HOURLY", 7_740).minWageRatio()).isEqualByComparingTo("75.0");
        assertThat(Stipend.of("UNSPECIFIED", 1_000_000).minWageRatio()).isNull();
        assertThat(Stipend.of("MONTHLY", null).minWageRatio()).isNull();
    }

    private static Set<String> quoted(String list) {
        Set<String> keys = new LinkedHashSet<>();
        Matcher m = Pattern.compile("'([A-Za-z]+)'").matcher(list);
        while (m.find()) {
            keys.add(m.group(1));
        }
        return keys;
    }
}
