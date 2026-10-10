package kr.ac.skuniv.coopradar.explore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import kr.ac.skuniv.coopradar.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 직무 탐색 실제 Claude 호출 확인(키를 받은 뒤 수동으로 돌린다. CI·평소 테스트에서는 건너뛴다, ADR-0031).
 * 예시 학생(E7 demo와 같은 경험 2개·카드 3개)으로 탐색 1번 = 순서 1회 + '왜 맞나요' 3회(약 $0.05).
 * 결과는 콘솔에만 찍는다. 확인하는 것: 구조화 출력·생각 단계 설정이 실제 API에서 받아들여지는지, 구절 확인을 통과하는지,
 * 응답 시간.
 *
 * <pre>
 * PowerShell:  $env:EXPLORE_LIVE_CHECK="1"; .\gradlew.bat test --tests "*ExploreLiveCheck*" -i
 * bash:        EXPLORE_LIVE_CHECK=1 ./gradlew test --tests '*ExploreLiveCheck*' -i
 * </pre>
 * 2026-10-10 실측(같은 입력): 순서 6.3초 · 5곳 모두 통과, '왜 맞나요' 3곳 5.9~7.1초 · 모두 통과.
 */
@EnabledIfEnvironmentVariable(named = "EXPLORE_LIVE_CHECK", matches = "1")
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ExploreLiveCheck {

    @Autowired
    MockMvc mvc;

    @Test
    void 예시_학생으로_실제_탐색() throws Exception {
        String guest = mvc.perform(post("/api/auth/guest").contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"STUDENT\"}"))
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(guest, "$.accessToken");
        long started = System.nanoTime();
        String body = mvc.perform(post("/api/explore").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"profile": {"departmentId": 43, "grade": 3, "completedSemesters": 5, "gpa": 3.4,
                                  "graduationExpected": false, "interestText": "뷰티 브랜드 마케팅, 뷰티 콘텐츠", "certificates": []},
                                 "experiences": [
                                   "학과 졸업작품 홍보용 인스타그램 계정을 1년 동안 운영하면서, 게시 시간과 첫 문장을 바꿔 가며 저장 수를 비교했어요.",
                                   "화장품 매장 아르바이트에서 손님이 자주 묻는 질문을 메모해 두고 진열을 바꿔 봤어요."],
                                 "cardIds": ["103-3", "105-3", "120-4"], "consent": true}"""))
                .andReturn().getResponse().getContentAsString();
        double seconds = (System.nanoTime() - started) / 1e9;
        System.out.printf("탐색 %.1f초 · source %s · fallback %s%n", seconds, JsonPath.read(body, "$.source"),
                JsonPath.<Object>read(body, "$.fallbackReason"));
        List<Map<String, Object>> items = JsonPath.read(body, "$.items");
        for (Map<String, Object> i : items) {
            System.out.println(i.get("rank") + " " + i.get("jobId") + " " + i.get("title") + " " + i.get("fit") + " "
                    + i.get("evidence") + (i.get("why") == null ? " · why 없음" : " · why " + i.get("why")));
        }
        assertThat(JsonPath.<String>read(body, "$.source")).isEqualTo("AI");
        assertThat(items).hasSizeGreaterThanOrEqualTo(3);
        assertThat(items.stream().filter(i -> i.get("why") != null).count()).isPositive();
    }
}
