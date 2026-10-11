package kr.ac.skuniv.coopradar.explore;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
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
import kr.ac.skuniv.coopradar.eligibility.EligibilityDtos.MajorMatch;
import kr.ac.skuniv.coopradar.eligibility.EligibilityDtos.Verdict;
import kr.ac.skuniv.coopradar.eligibility.EligibilityService;
import kr.ac.skuniv.coopradar.eligibility.EligibilityService.Judged;
import kr.ac.skuniv.coopradar.eligibility.ProfileInput;
import kr.ac.skuniv.coopradar.explore.ExploreDtos.Basis;
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
import kr.ac.skuniv.coopradar.explore.ExploreDtos.Link;
import kr.ac.skuniv.coopradar.explore.ExploreDtos.Source;
import kr.ac.skuniv.coopradar.explore.ExploreDtos.WhyText;
import kr.ac.skuniv.coopradar.explore.ExploreRepository.NewRun;
import kr.ac.skuniv.coopradar.explore.ExploreRepository.Run;
import kr.ac.skuniv.coopradar.explore.ExploreRepository.Saved;
import kr.ac.skuniv.coopradar.explore.ExploreRepository.Stored;
import kr.ac.skuniv.coopradar.explore.ExploreText.Piece;
import kr.ac.skuniv.coopradar.explore.ExploreText.Student;
import kr.ac.skuniv.coopradar.explore.ExploreVerifier.VerifiedJob;
import kr.ac.skuniv.coopradar.explore.ExploreVerifier.VerifiedLink;
import kr.ac.skuniv.coopradar.job.RoundRef;
import kr.ac.skuniv.coopradar.me.ProfileService;
import kr.ac.skuniv.coopradar.recommend.KeywordSimilarity;
import kr.ac.skuniv.coopradar.recommend.RecommendDtos.Blocked;
import kr.ac.skuniv.coopradar.recommend.RecommendDtos.Citation;
import kr.ac.skuniv.coopradar.recommend.RecommendDtos.Recommendation;
import kr.ac.skuniv.coopradar.recommend.RecommendDtos.Recommendations;
import kr.ac.skuniv.coopradar.recommend.RecommendDtos.SourceType;
import kr.ac.skuniv.coopradar.recommend.RecommendService;
import kr.ac.skuniv.coopradar.reference.ReferenceDates;
import kr.ac.skuniv.coopradar.reference.ReferenceDates.AsOf;
import kr.ac.skuniv.coopradar.reference.RoundService;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/**
 * 직무 탐색(#28~#32, ADR-0031·0038). 지원 조건은 규칙(#14)이 먼저 거르고, AI는 후보 안에서 학생 글과 업무의 짝만 찾고,
 * 순서는 규칙이 정한다.
 * <ol>
 *   <li>후보 = ELIGIBLE·NEEDS_CHECK이고 기준일에 마감되지 않은 직무. 지원 불가·마감 직무는 AI에 보내지 않는다</li>
 *   <li>경험 글을 가린 뒤(전화·이메일·긴 숫자) 학생 글 + 후보 직무 원문을 AI(Sonnet)에 보내 이어지는 자리와 짝(종류·학생
 *       구절·공고 구절)을 받는다. 학과·학년·평점은 보내지 않는다</li>
 *   <li>번호·구절·문장을 원문과 대조해 통과한 짝만 쓴다({@link ExploreVerifier}). 고른 카드가 나온 자리는 규칙이 넣는다</li>
 *   <li>순서는 규칙({@link #ORDER}): 근거 단계 → 선호 전공 → 이어진 짝 수 → 키워드 유사도 → 리스트 순번. 이어지는 근거가
 *       없는 자리는 넣지 않는다(0~5곳)</li>
 *   <li>1~3위 '왜 맞나요'를 동시에 만들고, 결과를 계정당 1건 저장한다(AI는 실행마다 순서가 조금 달라 다시 계산하지 않는다)</li>
 *   <li>키 없음·한도·실패·확인 실패면 적합도 추천(#15)으로 대신한다(200 + RULE)</li>
 * </ol>
 * AI 호출 중에는 DB 트랜잭션을 잡지 않는다.
 */
@Service
public class ExploreService {

    static final int WHY_AT_RUN = 3;
    /** 규칙 추천 이유. #15의 문장에는 학년·평점·학과가 들어가 저장하지 않는다(ADR-0008, 동의는 경험 글에 대한 것). */
    static final String RULE_REASON = "지원 조건과 적합도 점수로 고른 자리예요.";
    /** AI 이유 문장이 규칙을 어겼거나 AI가 쓰지 않은 자리(고른 카드가 나온 자리)의 단계 문장. */
    static final String SAME_TASK_REASON = "해 본 일과 같은 일을 하는 자리예요.";
    static final String TRANSFER_REASON = "해 본 일에서 쓴 능력이 이 자리 업무에 쓰여요.";
    static final String CARD_REASON = "고른 '하고 싶은 일'과 같은 일을 하는 자리예요.";
    static final String INTEREST_REASON = "관심 분야와 같은 일을 하는 자리예요.";

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
        AsOf asOf = ReferenceDates.recruit(rounds.current());
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
        ProfileInput profile = profiles.bodyOrSaved(user, req.profile());
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
        Pool pool = pool(round.id(), profile);
        Map<Integer, JobDoc> docs = docs(round.id());
        List<PickedCard> picked = resolveCards(cardIds, docs);
        List<String> cards = picked.stream().map(PickedCard::text).toList();
        Student student = new Student(experiences, cards, ExploreText.mask(profile.interestText()));

        Outcome outcome;
        if (pool.candidates().isEmpty()) {
            outcome = rule(profile, docs, Fallback.NO_CANDIDATES);
        } else if (!writer.available()) {
            outcome = rule(profile, docs, Fallback.NO_KEY);
        } else if (!limiter.tryAcquire(user.id(), ip)) {
            outcome = rule(profile, docs, Fallback.LIMITED);
        } else {
            outcome = ai(profile, pool, docs, student, picked);
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
        Fit fit = item.map(Stored::fit).orElse(Fit.NONE);
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

    private Outcome ai(ProfileInput profile, Pool pool, Map<Integer, JobDoc> docs, Student student, List<PickedCard> picked) {
        String system = "# 지원 조건을 맞춘 자리\n\n"
                + pool.candidates().stream().map(j -> ExploreText.rankCard(docs.get(j.result().jobId())))
                .collect(Collectors.joining("\n\n"))
                + "\n\n# 할 일\n" + rankPrompt;
        var draft = writer.rank(system, student.text(picked.stream().map(PickedCard::jobId).toList()));
        if (draft.isEmpty()) {
            return rule(profile, docs, Fallback.AI_ERROR);
        }
        ExploreVerifier.Verified verified = ExploreVerifier.links(draft.get(), pool.ids(), student, docs);
        Map<Integer, Linked> linked = new LinkedHashMap<>();
        for (VerifiedJob j : verified.jobs()) {
            linked.put(j.jobId(), new Linked(j.jobId(), new ArrayList<>(j.links()), j.reason()));
        }
        addPickedCards(linked, picked, pool, docs);
        if (linked.isEmpty() && verified.drafted() > 0) {
            return rule(profile, docs, Fallback.VERIFY_FAILED);
        }
        List<Linked> ordered = order(linked.values(), pool, docs, student);
        List<Stored> items = new ArrayList<>();
        for (Linked l : ordered.subList(0, Math.min(ExploreVerifier.LIMIT, ordered.size()))) {
            items.add(stored(items.size() + 1, l, pool, docs));
        }
        return new Outcome(Source.AI, null, items, whysAtRun(items, docs, student), List.of());
    }

    /** 대조를 통과한 짝을 모은 자리 하나(순서를 정하기 전). */
    private record Linked(int jobId, List<VerifiedLink> links, String reason) {

        /** 근거 단계 값: 같은 일 4 · 능력이 쓰이는 일 3 · 고른 카드 2 · 관심 분야 1. */
        static int tier(VerifiedLink l) {
            return switch (l.kind()) {
                case SAME_TASK -> 4;
                case TRANSFER -> 3;
                case WISH -> l.basis() == Basis.CARD ? 2 : 1;
                case NONE -> 0;
            };
        }

        int best() {
            return links.stream().mapToInt(Linked::tier).max().orElse(0);
        }

        /** 단계 높은 것부터(같은 단계 안은 AI가 쓴 순서). */
        List<VerifiedLink> sorted() {
            return links.stream().sorted(Comparator.comparingInt((VerifiedLink l) -> -tier(l))).toList();
        }
    }

    /**
     * 고른 카드가 나온 자리(그 카드와 거의 같은 글이 있는 후보 자리 포함)는 '고른 카드' 짝을 규칙으로 넣는다 — 카드는 그 자리
     * 원문이라 AI가 판단할 일이 없다. AI는 같은 일을 다른 말로 적은 다른 자리를 찾는다(ADR-0038).
     */
    private static void addPickedCards(Map<Integer, Linked> linked, List<PickedCard> picked, Pool pool,
                                       Map<Integer, JobDoc> docs) {
        for (PickedCard card : picked) {
            for (Integer jobId : pool.ids()) {
                JobDoc d = docs.get(jobId);
                boolean source = jobId == card.jobId();
                if (d == null || !(source || ExploreText.cardTexts(d).stream().anyMatch(t -> ExploreText.sameCard(t, card.text())))) {
                    continue;
                }
                String key = ExploreText.squashQuote(card.text());
                Optional<Piece> piece = ExploreText.pieces(d).stream()
                        .filter(p -> ExploreText.squash(p.text()).contains(key)).findFirst();
                VerifiedLink link = new VerifiedLink(Fit.WISH, Basis.CARD, card.text(),
                        piece.flatMap(p -> ExploreText.slice(p.text(), key)).orElse(null), piece.orElse(null));
                Linked l = linked.computeIfAbsent(jobId, k -> new Linked(k, new ArrayList<>(), null));
                boolean already = l.links().stream().anyMatch(x -> x.basis() == Basis.CARD
                        && ExploreText.squash(x.studentQuote()).equals(ExploreText.squash(card.text())));
                if (!already) {
                    l.links().add(link);
                }
            }
        }
    }

    /**
     * 순서 규칙(ADR-0038): ① 근거 단계(해 본 일과 같은 일 → 해 본 일의 능력이 쓰이는 일 → 고른 카드와 같은 일 → 관심 분야와
     * 같은 일) ② 선호 전공(일치 → 가까운 전공 → 전공 무관 → 그 밖) ③ 대조를 통과한 짝 수(근거가 많은 자리) ④ 학생 글 ↔ 직무
     * 원문 키워드 유사도(글자 2~3-gram TF-IDF) ⑤ 리스트 순번.
     */
    static final String ORDER = "근거 단계 → 선호 전공 → 짝 수 → 키워드 유사도 → 리스트 순번";

    private static List<Linked> order(java.util.Collection<Linked> linked, Pool pool, Map<Integer, JobDoc> docs,
                                      Student student) {
        Map<Integer, Judged> judged = new LinkedHashMap<>();
        pool.candidates().forEach(j -> judged.put(j.result().jobId(), j));
        List<String> corpus = new ArrayList<>();
        pool.candidates().forEach(j -> corpus.add(ExploreText.fullText(docs.get(j.result().jobId()))));
        corpus.add(student.joined());
        KeywordSimilarity model = new KeywordSimilarity(corpus);
        var query = model.vector(student.joined());
        Map<Integer, Double> keyword = new LinkedHashMap<>();
        for (Linked l : linked) {
            keyword.put(l.jobId(), KeywordSimilarity.cosine(query, model.vector(ExploreText.fullText(docs.get(l.jobId())))));
        }
        return linked.stream().sorted(Comparator
                        .comparingInt((Linked l) -> -l.best())
                        .thenComparingInt(l -> majorRank(judged.get(l.jobId()).result().majorMatch()))
                        .thenComparingInt(l -> -l.links().size())
                        .thenComparingDouble(l -> -keyword.get(l.jobId()))
                        .thenComparingInt(l -> judged.get(l.jobId()).requirement().listSeq()))
                .toList();
    }

    static int majorRank(MajorMatch m) {
        return switch (m) {
            case MATCH -> 0;
            case NEAR -> 1;
            case OPEN -> 2;
            case NOT_LISTED -> 3;
        };
    }

    private static Stored stored(int rank, Linked l, Pool pool, Map<Integer, JobDoc> docs) {
        JobDoc d = docs.get(l.jobId());
        List<VerifiedLink> sorted = l.sorted();
        VerifiedLink first = sorted.get(0);
        List<Link> links = sorted.stream()
                .map(x -> new Link(x.kind(), x.basis(), x.studentQuote(), x.jobQuote(),
                        x.piece() == null ? null : d.planTitle(), x.piece() == null ? null : d.page(x.piece().field())))
                .toList();
        String reason = l.reason() != null ? l.reason() : switch (first.kind()) {
            case SAME_TASK -> SAME_TASK_REASON;
            case TRANSFER -> TRANSFER_REASON;
            default -> first.basis() == Basis.CARD ? CARD_REASON : INTEREST_REASON;
        };
        return new Stored(rank, l.jobId(), pool.verdicts().get(l.jobId()), first.kind(), first.basis(),
                first.studentQuote(), first.jobQuote(), first.piece() == null ? null : d.planTitle(),
                first.piece() == null ? null : d.page(first.piece().field()), reason, links);
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
     * 적합도 추천(#15)으로 대신한다: 같은 직무·순서, fit은 NONE(AI가 이은 근거가 없다), 근거는 운영계획서 인용(없으면 첫
     * 인용), 이유는 {@link #RULE_REASON}. 추천이 0개면 #15의 blockedBy를 그대로 둔다.
     */
    private Outcome rule(ProfileInput profile, Map<Integer, JobDoc> docs, Fallback reason) {
        Recommendations recs = recommend.recommend(profile);
        List<Stored> items = new ArrayList<>();
        for (Recommendation r : recs.items()) {
            Citation c = r.citations().stream().filter(x -> x.sourceType() == SourceType.OPERATION_PLAN).findFirst()
                    .orElse(r.citations().isEmpty() ? null : r.citations().get(0));
            items.add(new Stored(items.size() + 1, r.jobId(), r.verdict(), Fit.NONE, null, null,
                    c == null ? null : c.quote(), c == null ? null : c.documentTitle(), c == null ? null : c.page(),
                    RULE_REASON, List.of()));
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
                    new Evidence(s.basis(), s.studentQuote(), s.jobQuote(), s.documentTitle(), s.page(), s.reason()),
                    s.links(), whys.get(s.jobId())));
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

    /** 고른 카드: 그 카드가 나온 자리와 카드 글. */
    private record PickedCard(int jobId, String text) {
    }

    /** 카드 id → 카드 글. 회차 직무 전부의 카드에서 찾는다(프로필이 바뀌어도 이미 고른 카드는 찾는다). */
    private static List<PickedCard> resolveCards(List<String> ids, Map<Integer, JobDoc> docs) {
        List<PickedCard> out = new ArrayList<>();
        for (String id : ids) {
            String[] parts = id == null ? new String[0] : id.split("-");
            JobDoc d;
            int n;
            if (parts.length != 2) {
                throw ApiException.invalid("cardIds", "POST /api/explore/cards의 id");
            }
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
            out.add(new PickedCard(d.jobId(), texts.get(n - 1)));
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
