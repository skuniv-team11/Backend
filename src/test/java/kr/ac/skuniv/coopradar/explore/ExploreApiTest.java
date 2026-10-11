package kr.ac.skuniv.coopradar.explore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kr.ac.skuniv.coopradar.TestcontainersConfiguration;
import kr.ac.skuniv.coopradar.contract.Contract;
import kr.ac.skuniv.coopradar.explore.ExploreDrafts.LinkDraft;
import kr.ac.skuniv.coopradar.explore.ExploreDrafts.LinkKind;
import kr.ac.skuniv.coopradar.explore.ExploreDrafts.LinkedJob;
import kr.ac.skuniv.coopradar.explore.ExploreDrafts.PhraseDraft;
import kr.ac.skuniv.coopradar.explore.ExploreDrafts.PointDraft;
import kr.ac.skuniv.coopradar.explore.ExploreDrafts.RankDraft;
import kr.ac.skuniv.coopradar.explore.ExploreDrafts.WhyDraft;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * docs/api #28~#32 직무 탐색(ADR-0031·0038). AI는 가짜(StubWriter)로 바꿔 끼운다 — 실제 Claude를 부르지 않는다.
 * 계정당 한도는 3회로 낮춰 둔다. 2026-2 실제 시드와 예시 학생(메이크업디자인학과 3학년, 학점 3.4)으로 본다 —
 * 이 학생의 후보(지원 가능·확인 필요, 마감 전)는 23곳이다(E7 demo와 같다).
 */
@SpringBootTest(properties = "app.explore.per-user-per-hour=3")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ExploreApiTest.StubConfig.class})
class ExploreApiTest {

    static final Set<Integer> CANDIDATES = Set.of(102, 103, 104, 105, 106, 107, 108, 109, 110, 111, 112, 113, 114, 115,
            116, 120, 121, 122, 123, 124, 135, 136, 137);
    static final String EXP1 = "학과 졸업작품 홍보용 인스타그램 계정을 1년 동안 운영하면서, 게시 시간과 첫 문장을 바꿔 가며 저장 수를 비교했어요.";
    static final String EXP2 = "화장품 매장 아르바이트에서 손님이 자주 묻는 질문을 메모해 두고 진열을 바꿔 봤어요.";
    /** 직무별로 원문에 그대로 있는 구절(가짜 AI가 쓴다). */
    static final Map<Integer, String> JOB_QUOTE = Map.of(
            122, "자사 SNS 채널 콘텐츠 운영 및 홍보 마케팅 업무 지원",
            123, "자사 SNS 콘텐츠 운영 및 홍보 마케팅 업무 지원",
            103, "인스타그램 등 SNS 채널 관리",
            105, "브랜드 숏폼 콘텐츠 기획",
            120, "기획전 제작 및 운영",
            102, "유튜브 채널 콘텐츠 기획 및 제작 서브");
    private static final Pattern JOB_HEAD = Pattern.compile("# 자리 \\[(\\d+)]");
    private static final AtomicInteger IP = new AtomicInteger(1);

    static final AtomicBoolean AVAILABLE = new AtomicBoolean(true);
    static final AtomicReference<Optional<RankDraft>> NEXT_RANK = new AtomicReference<>(Optional.empty());
    static final AtomicBoolean WHY_FAILS = new AtomicBoolean(false);
    static final AtomicInteger RANK_CALLS = new AtomicInteger();
    static final AtomicInteger WHY_CALLS = new AtomicInteger();
    static final AtomicReference<String> LAST_RANK_SYSTEM = new AtomicReference<>();
    static final AtomicReference<String> LAST_RANK_USER = new AtomicReference<>();
    static final List<String> WHY_USERS = new CopyOnWriteArrayList<>();

    @TestConfiguration
    static class StubConfig {
        @Bean
        @Primary
        ExploreWriter stubWriter() {
            return new ExploreWriter() {
                @Override
                public boolean available() {
                    return AVAILABLE.get();
                }

                @Override
                public String model() {
                    return "stub";
                }

                @Override
                public Optional<RankDraft> rank(String system, String user) {
                    RANK_CALLS.incrementAndGet();
                    LAST_RANK_SYSTEM.set(system);
                    LAST_RANK_USER.set(user);
                    return NEXT_RANK.get();
                }

                @Override
                public Optional<WhyDraft> why(String system, String user) {
                    WHY_CALLS.incrementAndGet();
                    WHY_USERS.add(user);
                    if (WHY_FAILS.get()) {
                        return Optional.empty();
                    }
                    Matcher m = JOB_HEAD.matcher(user);
                    int jobId = m.find() ? Integer.parseInt(m.group(1)) : 0;
                    String jq = JOB_QUOTE.getOrDefault(jobId, "원문에 없는 구절이에요");
                    return Optional.of(new WhyDraft("계정을 운영하며 반응을 비교해 본 경험이 이 자리의 일과 이어져요.",
                            List.of(new PointDraft("게시 요소를 바꿔 가며 저장 수를 비교한 경험은 콘텐츠 운영에 바로 쓸 수 있어요.",
                                            "게시 시간과 첫 문장을 바꿔 가며 저장 수를 비교했어요", jq),
                                    new PointDraft("지어낸 경험이라 빠져야 하는 문장이에요.", "동아리 회장을 3년 동안 맡았어요", jq)),
                            List.of(new PhraseDraft("현직자와 함께 새로운 일을 해 볼 수 있어요.", jq)),
                            new PhraseDraft("운영한 계정의 반응을 한 장으로 정리해 두면 좋아요.", jq)));
                }
            };
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    org.springframework.jdbc.core.simple.JdbcClient db;

    @BeforeEach
    void 초기화() {
        AVAILABLE.set(true);
        NEXT_RANK.set(Optional.empty());
        WHY_FAILS.set(false);
        RANK_CALLS.set(0);
        WHY_CALLS.set(0);
        WHY_USERS.clear();
    }

    static LinkDraft link(LinkKind kind, String studentQuote, String jobQuote) {
        return new LinkDraft(kind, studentQuote, jobQuote);
    }

    /**
     * 가짜 AI의 짝. 대조·단계·순서 규칙(ADR-0038)으로 122 · 123(같은 일) → 103 · 105(능력이 쓰이는 일) → 하고 싶은 일 순이 된다.
     * 122는 두 번 나와 짝이 합쳐지고(짝 2개라 123보다 앞), 103은 짝 3개(고른 카드 짝 포함)라 105보다 앞이다.
     */
    static RankDraft goodRanking() {
        return new RankDraft(List.of(
                new LinkedJob(122, List.of(link(LinkKind.같은일, "졸업작품 홍보용 인스타그램 계정을 1년 동안 운영하면서", JOB_QUOTE.get(122))),
                        "인스타그램 계정을 1년 동안 운영한 경험이 SNS 채널 운영 지원과 이어져요."),
                // 후보가 아니다(회계업무지원 — 이 학생은 지원 불가): 버린다
                new LinkedJob(117, List.of(link(LinkKind.쓰이는능력, "손님이 자주 묻는 질문을 메모해 두고",
                        "회사 재무제표 작성을 위한 기초 Data 집계 및 검토")), "질문을 정리한 경험이 자료 집계와 이어져요."),
                new LinkedJob(123, List.of(link(LinkKind.같은일, "게시 시간과 첫 문장을 바꿔 가며 저장 수를 비교했어요", JOB_QUOTE.get(123))),
                        "게시 요소를 바꿔 가며 성과를 비교한 경험이 콘텐츠 운영과 맞닿아 있어요."),
                // 학생 글에 없는 구절: 짝이 없어 자리째 빠진다
                new LinkedJob(124, List.of(link(LinkKind.같은일, "물류 창고에서 재고를 관리했어요", "자사 국제 유통 업무 및 물류 프로세스 참여")),
                        "재고 관리 경험이 물류 업무와 이어져요."),
                // 해요체가 아니다: 짝은 남고 이유는 단계 문장으로 바뀐다
                new LinkedJob(105, List.of(link(LinkKind.쓰이는능력, "학과 졸업작품 홍보용 인스타그램 계정", JOB_QUOTE.get(105))),
                        "숏폼 콘텐츠 기획과 이어진다."),
                new LinkedJob(103, List.of(
                        link(LinkKind.쓰이는능력, "손님이 자주 묻는 질문을 메모해 두고 진열을 바꿔 봤어요", JOB_QUOTE.get(103)),
                        link(LinkKind.쓰이는능력, "졸업작품 홍보용 인스타그램 계정을 1년 동안 운영하면서", JOB_QUOTE.get(103)),
                        // 해 본 일을 하고싶은일로 적은 어긋난 짝: 버린다
                        link(LinkKind.하고싶은일, "게시 시간과 첫 문장을 바꿔 가며", JOB_QUOTE.get(103))),
                        "손님 질문을 모아 본 경험이 인플루언서와 소통하며 SNS 채널을 관리하는 일에 쓰여요."),
                // 관심 분야 구절은 AI가 같은일이라 해도 하고 싶은 일(WISH)이다
                new LinkedJob(120, List.of(link(LinkKind.같은일, "뷰티 브랜드 SNS 마케팅", JOB_QUOTE.get(120))),
                        "관심 분야인 뷰티 브랜드 마케팅이 기획전 운영과 이어져요."),
                // 같은 직무 두 번: 짝을 합친다
                new LinkedJob(122, List.of(link(LinkKind.같은일, "게시 시간과 첫 문장을 바꿔 가며", JOB_QUOTE.get(122))),
                        "같은 자리를 또 골랐어요.")));
    }

    @Test
    void 카드는_후보_직무에서만_나오고_기관_이름을_가리며_계약_모양과_같다() throws Exception {
        String token = guestToken("STUDENT");
        String body = mvc.perform(post("/api/explore/cards").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(profileBody(5, "3.4")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidates.total").value(23))
                .andReturn().getResponse().getContentAsString();
        Contract.assertSameShape(body, Contract.responseExample("getExploreCards", 200, null));
        List<String> ids = JsonPath.read(body, "$.cards[*].id");
        List<String> texts = JsonPath.read(body, "$.cards[*].text");
        assertThat(ids).isNotEmpty().hasSizeLessThanOrEqualTo(60).doesNotHaveDuplicates();
        assertThat(ids).allSatisfy(id -> assertThat(CANDIDATES).contains(Integer.parseInt(id.split("-")[0])));
        assertThat(texts).allSatisfy(t -> assertThat(t.length()).isBetween(6, 50));
        assertThat(texts).noneMatch(t -> t.contains("소서") || t.contains("세정") || t.contains("오뷔엘알"));
        // 소서 국내 마케팅(122) 개요 항목, 오뷔엘알 국내 인플루언서(103)는 개요가 머리말뿐이라 주차 계획 항목
        assertThat(texts).contains("자사 SNS 채널 콘텐츠 운영 및 홍보 마케팅 업무 지원", "인플루언서 소통 및 관리");
        // 직무를 번갈아 놓는다: 앞 10개가 10개 직무에서 하나씩
        assertThat(ids.subList(0, 10).stream().map(id -> id.split("-")[0]).distinct().count()).isEqualTo(10);
    }

    @Test
    void AI_탐색은_확인을_통과한_짝만_남기고_단계_규칙으로_줄을_세우며_1에서_3위_설명을_함께_저장한다() throws Exception {
        String token = guestToken("STUDENT");
        NEXT_RANK.set(Optional.of(goodRanking()));
        String cards = mvc.perform(post("/api/explore/cards").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(profileBody(5, "3.4")))
                .andReturn().getResponse().getContentAsString();
        String card = JsonPath.<List<String>>read(cards, "$.cards[?(@.text == '인플루언서 소통 및 관리')].id").get(0);
        String body = explore(token, List.of(EXP1, EXP2 + " 연락은 010-1234-5678로 주세요."), List.of(card), true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("AI"))
                .andExpect(jsonPath("$.fallbackReason").isEmpty())
                .andExpect(jsonPath("$.judgedWith").value("RUN_PROFILE"))
                .andReturn().getResponse().getContentAsString();
        Contract.assertSameShape(body, Contract.responseExample("explore", 200, null));
        List<Integer> jobs = JsonPath.read(body, "$.items[*].jobId");
        assertThat(jobs.subList(0, 4)).containsExactly(122, 123, 103, 105);
        assertThat(JsonPath.<List<String>>read(body, "$.items[*].fit").subList(0, 4))
                .containsExactly("SAME_TASK", "SAME_TASK", "TRANSFER", "TRANSFER");
        assertThat(JsonPath.<List<String>>read(body, "$.items[*].evidence.basis").subList(0, 4)).containsOnly("EXPERIENCE");
        // 5위는 하고 싶은 일 단계: 고른 카드와 거의 같은 글이 있는 자리(카드 > 관심 분야)가 관심 분야로만 맞는 120보다 앞
        assertThat(JsonPath.<String>read(body, "$.items[4].fit")).isEqualTo("WISH");
        // 122는 두 번 나온 짝이 합쳐졌다
        assertThat(JsonPath.<List<String>>read(body, "$.items[0].links[*].studentQuote")).hasSize(2);
        // 103: 능력 짝 2개 + 고른 카드가 나온 자리라 규칙이 넣은 카드 짝(어긋난 하고싶은일 짝은 빠짐)
        assertThat(JsonPath.<List<String>>read(body, "$.items[2].links[*].kind")).containsExactly("TRANSFER", "TRANSFER", "WISH");
        assertThat(JsonPath.<String>read(body, "$.items[2].links[2].basis")).isEqualTo("CARD");
        assertThat(JsonPath.<String>read(body, "$.items[2].links[2].studentQuote")).isEqualTo("인플루언서 소통 및 관리");
        // 해요체가 아닌 AI 이유는 단계 문장으로
        assertThat(JsonPath.<String>read(body, "$.items[3].evidence.reason")).isEqualTo(ExploreService.TRANSFER_REASON);
        assertThat(JsonPath.<String>read(body, "$.items[0].evidence.documentTitle")).isEqualTo("소서 운영계획서");
        assertThat(JsonPath.<Integer>read(body, "$.items[0].evidence.page")).isNotNull();
        assertThat(JsonPath.<String>read(body, "$.items[0].institution.name")).isEqualTo("소서");
        // '왜 맞나요': 지어낸 구절이 든 point는 빠지고 나머지는 남는다
        assertThat(WHY_CALLS.get()).isEqualTo(3);
        assertThat(JsonPath.<List<Object>>read(body, "$.items[0].why.points")).hasSize(1);
        assertThat(JsonPath.<String>read(body, "$.items[2].why.points[0].jobQuote")).isEqualTo(JOB_QUOTE.get(103));
        // 가린 뒤 저장·전송: 전화번호는 [가림], AI에는 학과·학년·평점이 가지 않는다
        assertThat(JsonPath.<String>read(body, "$.input.experiences[1]")).contains("[가림]").doesNotContain("5678");
        assertThat(LAST_RANK_USER.get()).doesNotContain("5678").doesNotContain("메이크업").doesNotContain("3.4")
                .doesNotContain("학년").contains("하고 싶은 일(고른 카드): [103] 인플루언서 소통 및 관리")
                .contains("관심 분야: 뷰티 브랜드 SNS 마케팅");
        // 후보만 AI에 보낸다(지원 불가 117은 없다)
        assertThat(LAST_RANK_SYSTEM.get()).contains("[122] 마케팅팀 · 국내 마케팅").doesNotContain("[117]")
                .contains("# 할 일");

        // 다시 보기(#30): 체험 계정은 예시 프로필이 저장돼 있어 그 프로필로 다시 판정한다
        String mine = mvc.perform(get("/api/me/explore").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.judgedWith").value("SAVED_PROFILE"))
                .andExpect(jsonPath("$.hiddenCount").value(0))
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<Integer>>read(mine, "$.items[*].jobId")).isEqualTo(jobs);
        assertThat(JsonPath.<List<Object>>read(mine, "$.items[*].links")).isEqualTo(JsonPath.read(body, "$.items[*].links"));
        assertThat(JsonPath.<Integer>read(mine, "$.runId")).isEqualTo(JsonPath.<Integer>read(body, "$.runId"));

        // '왜 맞나요'(#32): 저장본은 AI를 다시 부르지 않는다
        why(token, 122).andExpect(status().isOk()).andExpect(jsonPath("$.fit").value("SAME_TASK"))
                .andExpect(jsonPath("$.why.summary").isNotEmpty());
        assertThat(WHY_CALLS.get()).isEqualTo(3);
        // 결과 밖 후보는 이때 만들고(NONE, '탐색 결과 밖'을 알린다) 저장한다
        String outside = why(token, 102).andExpect(status().isOk()).andExpect(jsonPath("$.fit").value("NONE"))
                .andReturn().getResponse().getContentAsString();
        Contract.assertSameShape(outside, Contract.responseExample("getExploreWhy", 200, null));
        assertThat(WHY_USERS.get(WHY_USERS.size() - 1)).contains("# 자리 [102]").contains("(탐색 결과 밖)");
        why(token, 102).andExpect(jsonPath("$.why.summary").isNotEmpty());
        assertThat(WHY_CALLS.get()).isEqualTo(4);
        // 후보가 아닌 직무 409, 없는 직무 404
        why(token, 117).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EXPLORE_NOT_CANDIDATE"));
        why(token, 99999).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("JOB_NOT_FOUND"));

        // 지우기(#31) → 없음
        mvc.perform(delete("/api/me/explore").header("Authorization", "Bearer " + token)).andExpect(status().isNoContent());
        mvc.perform(delete("/api/me/explore").header("Authorization", "Bearer " + token)).andExpect(status().isNoContent());
        mvc.perform(get("/api/me/explore").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("EXPLORE_NOT_FOUND"));
        why(token, 122).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("EXPLORE_NOT_FOUND"));
    }

    @Test
    void 저장한_프로필이_바뀌면_지원_불가가_된_자리는_숨기고_설명도_막는다() throws Exception {
        String token = guestToken("STUDENT");
        NEXT_RANK.set(Optional.of(goodRanking()));
        explore(token, List.of(EXP1, EXP2), List.of(), true).andExpect(jsonPath("$.items.length()").value(5));
        // 학점을 2.9로 저장 → 소서(학점 3.0 이상)는 지원 불가
        mvc.perform(put("/api/me/profile").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"departmentId": 43, "grade": 3, "completedSemesters": 5, "gpa": 2.9,
                                 "graduationExpected": false, "interestText": "뷰티 브랜드 SNS 마케팅", "consent": true}"""))
                .andExpect(status().isOk());
        String mine = mvc.perform(get("/api/me/explore").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.judgedWith").value("SAVED_PROFILE"))
                .andReturn().getResponse().getContentAsString();
        List<Integer> jobs = JsonPath.read(mine, "$.items[*].jobId");
        assertThat(jobs).doesNotContain(122, 123);
        assertThat(JsonPath.<Integer>read(mine, "$.hiddenCount")).isEqualTo(5 - jobs.size());
        assertThat(JsonPath.<List<Integer>>read(mine, "$.items[*].rank"))
                .isEqualTo(java.util.stream.IntStream.rangeClosed(1, jobs.size()).boxed().toList());
        why(token, 122).andExpect(status().isConflict());
    }

    @Test
    void AI가_실패하거나_확인을_못_넘거나_키가_없으면_규칙_추천으로_대신한다() throws Exception {
        String token = guestToken("STUDENT");
        String body = explore(token, List.of(EXP1), List.of(), true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("RULE"))
                .andExpect(jsonPath("$.fallbackReason").value("AI_ERROR"))
                .andReturn().getResponse().getContentAsString();
        List<Integer> jobs = JsonPath.read(body, "$.items[*].jobId");
        assertThat(jobs).isNotEmpty().allSatisfy(j -> assertThat(CANDIDATES).contains(j));
        assertThat(JsonPath.<List<Object>>read(body, "$.items[*].evidence.studentQuote")).containsOnlyNulls();
        assertThat(JsonPath.<List<Object>>read(body, "$.items[*].evidence.basis")).containsOnlyNulls();
        assertThat(JsonPath.<List<String>>read(body, "$.items[*].fit")).containsOnly("NONE");
        assertThat(JsonPath.<List<List<Object>>>read(body, "$.items[*].links")).allSatisfy(l -> assertThat(l).isEmpty());
        assertThat(JsonPath.<List<Object>>read(body, "$.items[*].why")).containsOnlyNulls();
        // 학년·평점·학과가 든 #15 문장은 저장하지 않는다(ADR-0008)
        assertThat(JsonPath.<List<String>>read(body, "$.items[*].evidence.reason"))
                .containsOnly(ExploreService.RULE_REASON);
        assertThat(db.sql("SELECT count(*) FROM explore_item WHERE reason ~ '학년|평점|학과'").query(Long.class).single())
                .isZero();
        assertThat(WHY_CALLS.get()).isZero();

        NEXT_RANK.set(Optional.of(new RankDraft(List.of(new LinkedJob(122,
                List.of(link(LinkKind.같은일, "지어낸 경험이에요 정말로", JOB_QUOTE.get(122))), "지어낸 경험이 이어져요.")))));
        explore(token, List.of(EXP1), List.of(), true).andExpect(jsonPath("$.fallbackReason").value("VERIFY_FAILED"));

        AVAILABLE.set(false);
        explore(token, List.of(EXP1), List.of(), true).andExpect(jsonPath("$.fallbackReason").value("NO_KEY"));
        assertThat(RANK_CALLS.get()).isEqualTo(2);
        // 키가 없으면 '왜 맞나요'도 만들지 않는다(200 + why null)
        why(token, 122).andExpect(status().isOk()).andExpect(jsonPath("$.why").isEmpty())
                .andExpect(jsonPath("$.fallbackReason").value("NO_KEY"));
    }

    @Test
    void AI가_이어지는_자리가_없다고_하면_억지로_채우지_않고_빈_결과다() throws Exception {
        String token = guestToken("STUDENT");
        NEXT_RANK.set(Optional.of(new RankDraft(List.of())));
        explore(token, List.of("학부 캡스톤에서 Spring Boot로 REST API 서버를 만들고 Docker로 배포했어요."), List.of(), true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("AI"))
                .andExpect(jsonPath("$.fallbackReason").isEmpty())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.blockedBy").isEmpty());
        assertThat(WHY_CALLS.get()).isZero();
    }

    @Test
    void 카드만_고르면_카드가_나온_자리는_규칙이_넣고_AI는_다른_말로_적은_자리를_찾는다() throws Exception {
        String token = guestToken("STUDENT");
        String cards = mvc.perform(post("/api/explore/cards").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(profileBody(5, "3.4")))
                .andReturn().getResponse().getContentAsString();
        List<String> picked = List.of(
                JsonPath.<List<String>>read(cards, "$.cards[?(@.text == '브랜드 숏폼 콘텐츠 기획')].id").get(0),
                JsonPath.<List<String>>read(cards, "$.cards[?(@.text == '제품 온라인 상세페이지 기획 및 제작')].id").get(0),
                JsonPath.<List<String>>read(cards, "$.cards[?(@.text == '기획전 제작 및 운영')].id").get(0));
        // AI는 카드와 같은 일을 다른 말로 적은 자리(102 유튜브 콘텐츠 기획)를 찾는다
        NEXT_RANK.set(Optional.of(new RankDraft(List.of(new LinkedJob(102,
                List.of(link(LinkKind.하고싶은일, "[105] 브랜드 숏폼 콘텐츠 기획", JOB_QUOTE.get(102))),
                "고른 숏폼 콘텐츠 기획이 유튜브 채널 콘텐츠를 기획하는 일과 같아요.")))));
        String body = explore(token, List.of(), picked, true)
                .andExpect(status().isOk()).andExpect(jsonPath("$.source").value("AI"))
                .andReturn().getResponse().getContentAsString();
        assertThat(LAST_RANK_USER.get()).contains("해 본 일: 쓰지 않음").contains("[105] 브랜드 숏폼 콘텐츠 기획");
        List<Integer> jobs = JsonPath.read(body, "$.items[*].jobId");
        assertThat(jobs).contains(105, 121, 120, 102);
        assertThat(JsonPath.<List<String>>read(body, "$.items[*].fit")).containsOnly("WISH");
        assertThat(JsonPath.<List<String>>read(body, "$.items[*].evidence.basis")).containsOnly("CARD");
        // 카드가 나온 자리는 AI 이유가 없어 단계 문장, AI가 찾은 자리는 AI 이유
        Map<String, Object> item105 = JsonPath.<List<Map<String, Object>>>read(body, "$.items[?(@.jobId == 105)]").get(0);
        assertThat(((Map<?, ?>) item105.get("evidence")).get("reason")).isEqualTo(ExploreService.CARD_REASON);
        Map<String, Object> item102 = JsonPath.<List<Map<String, Object>>>read(body, "$.items[?(@.jobId == 102)]").get(0);
        assertThat(((Map<?, ?>) item102.get("evidence")).get("studentQuote")).isEqualTo("브랜드 숏폼 콘텐츠 기획");
    }

    @Test
    void 설명_AI가_실패하면_탐색은_그대로_주고_그_자리_설명만_비운다() throws Exception {
        String token = guestToken("STUDENT");
        NEXT_RANK.set(Optional.of(goodRanking()));
        WHY_FAILS.set(true);
        String body = explore(token, List.of(EXP1), List.of(), true)
                .andExpect(status().isOk()).andExpect(jsonPath("$.source").value("AI"))
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<Object>>read(body, "$.items[*].why")).containsOnlyNulls();
        why(token, 122).andExpect(status().isOk()).andExpect(jsonPath("$.why").isEmpty())
                .andExpect(jsonPath("$.fallbackReason").value("AI_ERROR"));
        // 다시 열면 그때 만든다
        WHY_FAILS.set(false);
        why(token, 122).andExpect(status().isOk()).andExpect(jsonPath("$.why.summary").isNotEmpty());
    }

    @Test
    void 계정당_한도를_넘으면_AI_없이_규칙_추천이고_설명도_LIMITED() throws Exception {
        String token = guestToken("STUDENT");
        NEXT_RANK.set(Optional.of(goodRanking()));
        for (int i = 0; i < 3; i++) {
            explore(token, List.of(EXP1), List.of(), true).andExpect(jsonPath("$.source").value("AI"));
        }
        explore(token, List.of(EXP1), List.of(), true).andExpect(jsonPath("$.fallbackReason").value("LIMITED"));
        why(token, 105).andExpect(status().isOk()).andExpect(jsonPath("$.fallbackReason").value("LIMITED"));
        assertThat(RANK_CALLS.get()).isEqualTo(3);
    }

    @Test
    void 후보가_없으면_AI를_부르지_않고_막은_조건을_준다() throws Exception {
        String token = guestToken("STUDENT");
        NEXT_RANK.set(Optional.of(goodRanking()));
        String body = mvc.perform(post("/api/explore").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"profile\": " + profile(3, "3.4") + ", \"experiences\": [\"" + EXP1 + "\"], \"consent\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fallbackReason").value("NO_CANDIDATES"))
                .andExpect(jsonPath("$.candidates.total").value(0))
                .andExpect(jsonPath("$.items").isEmpty())
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(body, "$.blockedBy[*].item")).contains("이수 학기");
        assertThat(RANK_CALLS.get()).isZero();
    }

    @Test
    void 동의_없음_입력_부족_없는_카드는_400_센터는_403() throws Exception {
        String token = guestToken("STUDENT");
        explore(token, List.of(EXP1), List.of(), false)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CONSENT_REQUIRED"));
        explore(token, List.of(), List.of("122-1", "123-1"), true)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields[0].field").value("experiences"));
        explore(token, List.of(EXP1), List.of("122-99"), true)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields[0].field").value("cardIds"));
        explore(token, List.of(EXP1), List.of("122-1", "122-1", "123-1"), true)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields[0].field").value("cardIds"));
        // 모양이 틀린 카드 id('-' 없음)는 본문 검증에서 400
        explore(token, List.of(EXP1), List.of("122", "122-1", "123-1"), true)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields[0].field").value("cardIds[0]"));
        explore(token, List.of("너무 짧아요"), List.of(), true)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields[0].field").value("experiences[0]"));
        assertThat(RANK_CALLS.get()).isZero();
        String center = guestToken("CENTER");
        mvc.perform(post("/api/explore/cards").header("Authorization", "Bearer " + center)
                        .contentType(MediaType.APPLICATION_JSON).content(profileBody(5, "3.4")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/me/explore").header("Authorization", "Bearer " + center)).andExpect(status().isForbidden());
    }

    @Test
    void 프로필을_빼면_저장한_프로필로_카드와_탐색을_만든다() throws Exception {
        String token = guestToken("STUDENT");   // 예시 프로필이 저장돼 있다(본문 profile과 같은 학생)
        mvc.perform(post("/api/explore/cards").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.candidates.total").value(CANDIDATES.size()));
        NEXT_RANK.set(Optional.of(goodRanking()));
        mvc.perform(post("/api/explore").header("Authorization", "Bearer " + token).header("CF-Connecting-IP", "198.51.100.8")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"experiences\": [\"" + EXP1 + "\"], \"cardIds\": [], \"consent\": true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.source").value("AI"))
                .andExpect(jsonPath("$.candidates.total").value(CANDIDATES.size()));
        // 저장한 프로필이 없으면 404
        String signup = mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"x." + java.util.UUID.randomUUID() + "@example.com\",\"password\":\"password-8\"}"))
                .andReturn().getResponse().getContentAsString();
        String member = JsonPath.read(signup, "$.accessToken");
        mvc.perform(post("/api/explore/cards").header("Authorization", "Bearer " + member)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PROFILE_NOT_FOUND"));
        mvc.perform(post("/api/explore").header("Authorization", "Bearer " + member).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"experiences\": [\"" + EXP1 + "\"], \"consent\": true}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PROFILE_NOT_FOUND"));
    }

    private ResultActions explore(String token, List<String> experiences, List<String> cardIds, boolean consent)
            throws Exception {
        String exps = String.join(",", experiences.stream().map(e -> "\"" + e + "\"").toList());
        String cards = String.join(",", cardIds.stream().map(c -> "\"" + c + "\"").toList());
        return mvc.perform(post("/api/explore").header("Authorization", "Bearer " + token)
                .header("CF-Connecting-IP", "198.51.100.7")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"profile\": " + profile(5, "3.4") + ", \"experiences\": [" + exps + "], \"cardIds\": [" + cards
                        + "], \"consent\": " + consent + "}"));
    }

    private ResultActions why(String token, int jobId) throws Exception {
        return mvc.perform(get("/api/me/explore/jobs/" + jobId + "/why").header("Authorization", "Bearer " + token));
    }

    private static String profileBody(int semesters, String gpa) {
        return "{\"profile\": " + profile(semesters, gpa) + "}";
    }

    private static String profile(int semesters, String gpa) {
        return """
                {"departmentId": 43, "grade": 3, "completedSemesters": %d, "gpa": %s, "graduationExpected": false,
                 "interestText": "뷰티 브랜드 SNS 마케팅", "homeAreaCode": null, "certificates": []}""".formatted(semesters, gpa);
    }

    private String guestToken(String role) throws Exception {
        String body = mvc.perform(post("/api/auth/guest").header("CF-Connecting-IP", "198.51.100." + (100 + IP.getAndIncrement()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"" + role + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }
}
