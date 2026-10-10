package kr.ac.skuniv.coopradar.explore;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import kr.ac.skuniv.coopradar.auth.AuthUser;
import kr.ac.skuniv.coopradar.common.ApiException;
import kr.ac.skuniv.coopradar.common.ErrorCode;
import kr.ac.skuniv.coopradar.eligibility.EligibilityDtos.Verdict;
import kr.ac.skuniv.coopradar.eligibility.EligibilityService;
import kr.ac.skuniv.coopradar.eligibility.EligibilityService.Judged;
import kr.ac.skuniv.coopradar.eligibility.ProfileInput;
import kr.ac.skuniv.coopradar.explore.ExploreDtos.Candidates;
import kr.ac.skuniv.coopradar.explore.ExploreDtos.Cards;
import kr.ac.skuniv.coopradar.explore.ExploreDtos.Evidence;
import kr.ac.skuniv.coopradar.explore.ExploreDtos.Explore;
import kr.ac.skuniv.coopradar.explore.ExploreDtos.Fallback;
import kr.ac.skuniv.coopradar.explore.ExploreDtos.Fit;
import kr.ac.skuniv.coopradar.explore.ExploreDtos.Input;
import kr.ac.skuniv.coopradar.explore.ExploreDtos.Item;
import kr.ac.skuniv.coopradar.explore.ExploreDtos.JobWhy;
import kr.ac.skuniv.coopradar.explore.ExploreDtos.JudgedWith;
import kr.ac.skuniv.coopradar.explore.ExploreDtos.Source;
import kr.ac.skuniv.coopradar.explore.ExploreDtos.WhyText;
import kr.ac.skuniv.coopradar.explore.ExploreRepository.NewRun;
import kr.ac.skuniv.coopradar.explore.ExploreRepository.Run;
import kr.ac.skuniv.coopradar.explore.ExploreRepository.Saved;
import kr.ac.skuniv.coopradar.explore.ExploreRepository.Stored;
import kr.ac.skuniv.coopradar.explore.ExploreText.Student;
import kr.ac.skuniv.coopradar.explore.ExploreVerifier.Ranked;
import kr.ac.skuniv.coopradar.job.RoundRef;
import kr.ac.skuniv.coopradar.me.ProfileService;
import kr.ac.skuniv.coopradar.recommend.RecommendDtos.Blocked;
import kr.ac.skuniv.coopradar.recommend.RecommendDtos.Citation;
import kr.ac.skuniv.coopradar.recommend.RecommendDtos.Recommendation;
import kr.ac.skuniv.coopradar.recommend.RecommendDtos.Recommendations;
import kr.ac.skuniv.coopradar.recommend.RecommendDtos.SourceType;
import kr.ac.skuniv.coopradar.recommend.RecommendService;
import kr.ac.skuniv.coopradar.reference.RoundService;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/**
 * 직무 탐색(#28~#32, ADR-0031). 지원 조건은 규칙(#14)이 먼저 거르고, AI는 후보 안에서 경험과 이어지는 자리를 고른다.
 * <ol>
 *   <li>후보 = ELIGIBLE·NEEDS_CHECK이고 기준일에 마감되지 않은 직무. 지원 불가·마감 직무는 AI에 보내지 않는다</li>
 *   <li>경험 글을 가린 뒤(전화·이메일·긴 숫자) 학생 글 + 후보 직무 원문을 AI(Sonnet)에 보내 5곳과 구절·이유를 받는다.
 *       학과·학년·평점은 보내지 않는다</li>
 *   <li>번호·구절·문장을 원문과 대조해 통과한 것만 쓴다({@link ExploreVerifier}). 맞는 정도는 순위로 정한다</li>
 *   <li>1~3위 '왜 맞나요'를 동시에 만들고, 결과를 계정당 1건 저장한다(AI는 실행마다 순서가 조금 달라 다시 계산하지 않는다)</li>
 *   <li>키 없음·한도·실패·확인 실패면 적합도 추천(#15)으로 대신한다(200 + RULE)</li>
 * </ol>
 * AI 호출 중에는 DB 트랜잭션을 잡지 않는다.
 */
@Service
public class ExploreService {

    static final int WHY_AT_RUN = 3;

    private final EligibilityService eligibility;
    private final RecommendService recommend;
    private final RoundService rounds;
    private final ProfileService profiles;
    private final ExploreRepository repository;
    private final ExploreWriter writer;
    private final ExploreLimiter limiter;
    private final String rankPrompt;
    private final String whyPrompt;
    private final String promptVersion;

    public ExploreService(EligibilityService eligibility, RecommendService recommend, RoundService rounds,
                          ProfileService profiles, ExploreRepository repository, ExploreWriter writer,
                          ExploreLimiter limiter) {
        this.eligibility = eligibility;
        this.recommend = recommend;
        this.rounds = rounds;
        this.profiles = profiles;
        this.repository = repository;
        this.writer = writer;
        this.limiter = limiter;
        this.rankPrompt = load("prompts/explore-rank-system.md");
        this.whyPrompt = load("prompts/explore-why-system.md");
        this.promptVersion = sha256(rankPrompt + "\u0001" + whyPrompt).substring(0, 12);
    }

    /** 후보와 그 판정(리스트 순번 순). */
    private record Pool(List<Judged> candidates, Map<Integer, Verdict> verdicts, Candidates counts) {

        Set<Integer> ids() {
            return verdicts.keySet();
        }
    }

    private Pool pool(int roundId, ProfileInput profile) {
        LocalDate asOf = rounds.current().replay().defaultAsOf();
        List<Judged> candidates = eligibility.judgeAll(roundId, profile).stream()
                .filter(j -> j.result().verdict() != Verdict.INELIGIBLE)
                .filter(j -> !EligibilityService.closedOn(j, asOf))
                .toList();
        Map<Integer, Verdict> verdicts = new LinkedHashMap<>();
        candidates.forEach(j -> verdicts.put(j.result().jobId(), j.result().verdict()));
        int eligible = (int) candidates.stream().filter(j -> j.result().verdict() == Verdict.ELIGIBLE).count();
        return new Pool(candidates, verdicts, new Candidates(candidates.size(), eligible, candidates.size() - eligible));
    }

    /** #28 '하고 싶은 일' 카드. */
    public Cards cards(ProfileInput profile) {
        var round = rounds.current();
        Pool pool = pool(round.id(), profile);
        Map<Integer, JobDoc> docs = docs(round.id());
        List<JobDoc> candidates = docs.values().stream().filter(d -> pool.ids().contains(d.jobId())).toList();
        return new Cards(new RoundRef(round.id(), round.termCode()), pool.counts(), ExploreText.cards(candidates));
    }

    /** #29 탐색. */
    public Explore explore(AuthUser user, String ip, ExploreRequest req) {
        if (!Boolean.TRUE.equals(req.consent())) {
            throw new ApiException(ErrorCode.CONSENT_REQUIRED, "경험 글을 AI 분석에 쓰고 결과와 함께 저장하는 데 동의해 주세요");
        }
        List<String> experiences = (req.experiences() == null ? List.<String>of() : req.experiences()).stream()
                .map(ExploreText::mask).toList();
        List<String> cardIds = req.cardIds() == null ? List.of() : req.cardIds();
        if (new HashSet<>(cardIds).size() != cardIds.size()) {
            throw ApiException.invalid("cardIds", "같은 카드를 두 번 고를 수 없어요");
        }
        if (experiences.isEmpty() && cardIds.size() < 3) {
            throw ApiException.invalid("experiences", "해 본 일을 하나 이상 쓰거나 하고 싶은 일을 3개 이상 고르세요");
        }
        var round = rounds.current();
        Pool pool = pool(round.id(), req.profile());
        Map<Integer, JobDoc> docs = docs(round.id());
        List<String> cards = resolveCards(cardIds, docs);
        Student student = new Student(experiences, cards, ExploreText.mask(req.profile().interestText()));

        Outcome outcome;
        if (pool.candidates().isEmpty()) {
            outcome = rule(req.profile(), docs, Fallback.NO_CANDIDATES);
        } else if (!writer.available()) {
            outcome = rule(req.profile(), docs, Fallback.NO_KEY);
        } else if (!limiter.tryAcquire(user.id(), ip)) {
            outcome = rule(req.profile(), docs, Fallback.LIMITED);
        } else {
            outcome = ai(req.profile(), pool, docs, student);
        }
        NewRun run = new NewRun(round.id(), experiences, cards, student.interestText(), outcome.source(),
                outcome.fallback(), List.copyOf(pool.ids()), pool.counts().eligible(), pool.counts().needsCheck(),
                outcome.blockedBy(), outcome.source() == Source.AI ? writer.model() : null, promptVersion);
        Saved saved = repository.replace(user.id(), run, outcome.items(), outcome.whys());
        return view(new Run(saved.id(), round.id(), round.termCode(), experiences, cards, student.interestText(), outcome.source(),
                        outcome.fallback(), List.copyOf(pool.ids()), pool.counts().eligible(), pool.counts().needsCheck(),
                        outcome.blockedBy(), saved.createdAt()),
                outcome.items(), outcome.whys(), docs, null);
    }

    /** #30 저장된 탐색. 저장한 프로필이 있으면 그 프로필로 다시 판정해 후보에서 빠진 자리를 숨긴다. */
    public Explore mine(AuthUser user) {
        Run run = repository.findRun(user.id())
                .orElseThrow(() -> new ApiException(ErrorCode.EXPLORE_NOT_FOUND, "저장된 탐색 결과가 없어요"));
        Optional<Pool> now = profiles.savedInput(user).map(p -> pool(run.roundId(), p));
        return view(run, repository.items(run.id()), repository.whys(run.id()), docs(run.roundId()), now.orElse(null));
    }

    /** #31 */
    public void delete(AuthUser user) {
        repository.delete(user.id());
    }

    /** #32 직무 상세 '왜 맞나요'. 저장본이 있으면 주고, 없으면 이때 만든다. */
    public JobWhy why(AuthUser user, String ip, int jobId) {
        Run run = repository.findRun(user.id())
                .orElseThrow(() -> new ApiException(ErrorCode.EXPLORE_NOT_FOUND, "저장된 탐색 결과가 없어요"));
        Map<Integer, JobDoc> docs = docs(run.roundId());
        JobDoc doc = docs.get(jobId);
        if (doc == null) {
            throw new ApiException(ErrorCode.JOB_NOT_FOUND, "없는 직무예요");
        }
        Set<Integer> candidates = profiles.savedInput(user).map(p -> pool(run.roundId(), p).ids())
                .orElseGet(() -> Set.copyOf(run.candidateJobIds()));
        if (!candidates.contains(jobId)) {
            throw new ApiException(ErrorCode.EXPLORE_NOT_CANDIDATE, "지금은 지원할 수 없거나 마감된 직무라 설명하지 않아요");
        }
        Optional<Stored> item = repository.items(run.id()).stream().filter(i -> i.jobId() == jobId).findFirst();
        Fit fit = item.map(Stored::fit).orElse(Fit.WEAK);
        WhyText stored = repository.whys(run.id()).get(jobId);
        if (stored != null) {
            return new JobWhy(jobId, fit, stored, null);
        }
        if (!writer.available()) {
            return new JobWhy(jobId, fit, null, Fallback.NO_KEY);
        }
        if (!limiter.tryAcquire(user.id(), ip)) {
            return new JobWhy(jobId, fit, null, Fallback.LIMITED);
        }
        Student student = new Student(run.experiences(), run.cards(), run.interestText());
        Generated g = generateWhy(student, doc, item.isEmpty());
        if (g.why() == null) {
            return new JobWhy(jobId, fit, null, g.fallback());
        }
        repository.insertWhy(run.id(), jobId, g.why());
        return new JobWhy(jobId, fit, g.why(), null);
    }

    // ───────────────────────── 만들기 ─────────────────────────

    private record Outcome(Source source, Fallback fallback, List<Stored> items, Map<Integer, WhyText> whys,
                           List<Blocked> blockedBy) {
    }

    private record Generated(WhyText why, Fallback fallback) {
    }

    private Outcome ai(ProfileInput profile, Pool pool, Map<Integer, JobDoc> docs, Student student) {
        String system = "# 지원 조건을 맞춘 자리\n\n"
                + pool.candidates().stream().map(j -> ExploreText.rankCard(docs.get(j.result().jobId())))
                .collect(Collectors.joining("\n\n"))
                + "\n\n# 할 일\n" + rankPrompt;
        var draft = writer.rank(system, student.text());
        if (draft.isEmpty()) {
            return rule(profile, docs, Fallback.AI_ERROR);
        }
        List<Ranked> ranked = ExploreVerifier.ranking(draft.get(), pool.ids(), student, docs);
        if (ranked.isEmpty()) {
            return rule(profile, docs, Fallback.VERIFY_FAILED);
        }
        List<Stored> items = new ArrayList<>();
        for (Ranked r : ranked) {
            int rank = items.size() + 1;
            JobDoc d = docs.get(r.jobId());
            items.add(new Stored(rank, r.jobId(), pool.verdicts().get(r.jobId()), rank <= 2 ? Fit.STRONG : Fit.GOOD,
                    r.studentQuote(), r.jobQuote(), d.planTitle(), d.page(r.piece().field()), r.reason()));
        }
        return new Outcome(Source.AI, null, items, whysAtRun(items, docs, student), List.of());
    }

    /** 1~3위 '왜 맞나요'를 동시에 만든다. 못 만든 곳은 비워 두고(직무 상세에서 다시 만든다) 탐색은 그대로 준다. */
    private Map<Integer, WhyText> whysAtRun(List<Stored> items, Map<Integer, JobDoc> docs, Student student) {
        Map<Integer, WhyText> out = new LinkedHashMap<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Map<Integer, Future<Generated>> futures = new LinkedHashMap<>();
            for (Stored i : items.subList(0, Math.min(WHY_AT_RUN, items.size()))) {
                futures.put(i.jobId(), pool.submit(() -> generateWhy(student, docs.get(i.jobId()), false)));
            }
            for (var e : futures.entrySet()) {
                try {
                    Generated g = e.getValue().get(2, TimeUnit.MINUTES);
                    if (g.why() != null) {
                        out.put(e.getKey(), g.why());
                    }
                } catch (Exception ex) {
                    if (ex instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    // 이 곳만 비워 둔다
                }
            }
        }
        return out;
    }

    private Generated generateWhy(Student student, JobDoc doc, boolean outside) {
        var draft = writer.why(whyPrompt, ExploreText.whyMessage(student, doc, outside));
        if (draft.isEmpty()) {
            return new Generated(null, Fallback.AI_ERROR);
        }
        return ExploreVerifier.why(draft.get(), student, doc)
                .map(w -> new Generated(w, null))
                .orElseGet(() -> new Generated(null, Fallback.VERIFY_FAILED));
    }

    /**
     * 적합도 추천(#15)으로 대신한다: 같은 직무·순서, HIGH → STRONG, MEDIUM → GOOD, 근거는 운영계획서 인용(없으면 첫 인용),
     * 이유는 기본 문장. 추천이 0개면 #15의 blockedBy를 그대로 둔다.
     */
    private Outcome rule(ProfileInput profile, Map<Integer, JobDoc> docs, Fallback reason) {
        Recommendations recs = recommend.recommend(profile);
        List<Stored> items = new ArrayList<>();
        for (Recommendation r : recs.items()) {
            Citation c = r.citations().stream().filter(x -> x.sourceType() == SourceType.OPERATION_PLAN).findFirst()
                    .orElse(r.citations().isEmpty() ? null : r.citations().get(0));
            items.add(new Stored(items.size() + 1, r.jobId(), r.verdict(),
                    r.fit() == kr.ac.skuniv.coopradar.recommend.RecommendDtos.Fit.HIGH ? Fit.STRONG : Fit.GOOD, null,
                    c == null ? null : c.quote(), c == null ? null : c.documentTitle(), c == null ? null : c.page(),
                    r.reasonTemplate()));
        }
        return new Outcome(Source.RULE, reason, items, Map.of(), items.isEmpty() ? recs.blockedBy() : List.of());
    }

    // ───────────────────────── 보여 주기 ─────────────────────────

    /**
     * @param now 저장한 프로필로 다시 판정한 후보(없으면 탐색 때 판정 그대로)
     */
    private Explore view(Run run, List<Stored> stored, Map<Integer, WhyText> whys, Map<Integer, JobDoc> docs, Pool now) {
        List<Item> items = new ArrayList<>();
        int hidden = 0;
        for (Stored s : stored) {
            Verdict verdict = s.verdict();
            if (now != null) {
                verdict = now.verdicts().get(s.jobId());
                if (verdict == null) {
                    hidden++;
                    continue;
                }
            }
            JobDoc d = docs.get(s.jobId());
            if (d == null) {
                hidden++;
                continue;
            }
            items.add(new Item(items.size() + 1, s.jobId(), d.title(), d.team(), d.institutionRef(), verdict, s.fit(),
                    new Evidence(s.studentQuote(), s.jobQuote(), s.documentTitle(), s.page(), s.reason()),
                    whys.get(s.jobId())));
        }
        Candidates counts = now != null ? now.counts()
                : new Candidates(run.eligible() + run.needsCheck(), run.eligible(), run.needsCheck());
        return new Explore(run.id(), run.createdAt(), new RoundRef(run.roundId(), run.termCode()), run.source(),
                run.fallback(),
                now != null ? JudgedWith.SAVED_PROFILE : JudgedWith.RUN_PROFILE, hidden,
                new Input(run.experiences(), run.cards(), run.interestText()), counts, items,
                items.isEmpty() ? run.blockedBy() : List.of());
    }

    private Map<Integer, JobDoc> docs(int roundId) {
        return repository.jobDocs(roundId).stream()
                .collect(Collectors.toMap(JobDoc::jobId, Function.identity(), (a, b) -> a, LinkedHashMap::new));
    }

    /** 카드 id → 카드 글. 회차 직무 전부의 카드에서 찾는다(프로필이 바뀌어도 이미 고른 카드는 찾는다). */
    private static List<String> resolveCards(List<String> ids, Map<Integer, JobDoc> docs) {
        List<String> out = new ArrayList<>();
        for (String id : ids) {
            String[] parts = id.split("-");
            JobDoc d;
            int n;
            try {
                d = docs.get(Integer.parseInt(parts[0]));
                n = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                throw ApiException.invalid("cardIds", "POST /api/explore/cards의 id");
            }
            List<String> texts = d == null ? List.of() : ExploreText.cardTexts(d);
            if (n < 1 || n > texts.size()) {
                throw ApiException.invalid("cardIds", "POST /api/explore/cards의 id");
            }
            out.add(texts.get(n - 1));
        }
        return out;
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String load(String path) {
        try {
            return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
