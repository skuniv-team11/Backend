package kr.ac.skuniv.coopradar.recommend;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import kr.ac.skuniv.coopradar.auth.AuthUser;
import kr.ac.skuniv.coopradar.common.ApiException;
import kr.ac.skuniv.coopradar.common.ErrorCode;
import kr.ac.skuniv.coopradar.eligibility.EligibilityDtos.EligibilityJob;
import kr.ac.skuniv.coopradar.eligibility.EligibilityDtos.Layer;
import kr.ac.skuniv.coopradar.eligibility.EligibilityDtos.MajorMatch;
import kr.ac.skuniv.coopradar.eligibility.EligibilityDtos.ReasonLine;
import kr.ac.skuniv.coopradar.eligibility.EligibilityDtos.Result;
import kr.ac.skuniv.coopradar.eligibility.EligibilityDtos.Verdict;
import kr.ac.skuniv.coopradar.eligibility.EligibilityService;
import kr.ac.skuniv.coopradar.eligibility.EligibilityService.Judged;
import kr.ac.skuniv.coopradar.eligibility.ProfileInput;
import kr.ac.skuniv.coopradar.recommend.FitScorer.Scored;
import kr.ac.skuniv.coopradar.recommend.RecommendService.Explained;
import kr.ac.skuniv.coopradar.recommend.RecommendDtos.Citation;
import kr.ac.skuniv.coopradar.recommend.RecommendDtos.ReasonSource;
import kr.ac.skuniv.coopradar.recommend.RecommendDtos.RecommendationReason;
import kr.ac.skuniv.coopradar.recommend.RecommendRepository.JobFacts;
import kr.ac.skuniv.coopradar.reference.CodeLabels;
import kr.ac.skuniv.coopradar.reference.RoundService;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/**
 * 추천 이유 문장(#16). 규칙 템플릿을 바탕에 두고, LLM(Haiku)이 쓴 문장은 검증을 통과할 때만 내보낸다.
 * <ul>
 *   <li>LLM에는 학과·학년·관심 분야만 보낸다(평점·사는 곳은 보내지 않는다, ADR-0008)</li>
 *   <li>검증: 길이(10~200자) · 해요체로 끝남('습니다'·'당신' 없음) · 근거 번호가 준 범위 안 · 따옴표로 인용한 글이
 *       근거·직무 원문에 실제로 있음. 하나라도 어기면 TEMPLATE. 마크다운 별표는 지운다</li>
 *   <li>키 없음·실패·제한 시간·호출 제한·지원 불가 직무 → 200 + TEMPLATE(화면 유지)</li>
 *   <li>캐시는 메모리(프롬프트 버전 + 모델 + 직무 + 학과·학년·관심 분야 + 판정·적합도의 해시). 서버가 다시 뜨면 비워진다</li>
 *   <li>근거 인용·기본 문장은 추천(#15)과 같다(ADR-0020). LLM 문장 뒤에는 선호 전공에 소속 학과가 있는지·같은 팀 직무와의
 *       차이를 규칙 문장으로 붙인다({@link #finish}). LLM은 선호 전공을 말하지 않는다</li>
 * </ul>
 */
@Service
public class ReasonService {

    static final int MIN_LENGTH = 10;
    static final int MAX_LENGTH = 200;
    static final String INELIGIBLE_TEXT = "학교 규정에 맞지 않아 지금은 지원할 수 없는 자리예요.";
    private static final Pattern QUOTED = Pattern.compile("[\"“'‘「『]([^\"”'’」』]{4,})[\"”'’」』]");
    private static final int FACT_LIMIT = 600;
    private static final Pattern DEPARTMENT_SUFFIX = Pattern.compile("(학과|학부|전공)$");
    private static final Pattern TRAILING_PARTICLE = Pattern.compile("(에서|으로|에|을|를|이|가|의|와|과|은|는|도|로)$");
    private static final String INDUSTRY_NOUNS = "(브랜드|기업|회사|업계|업종|시장|산업)";
    /** 원문보다 넓히는 말. 근거·직무 원문에 없으면 쓰지 않는다(예: '뷰티 마케팅의 전 과정을 경험'). 공백은 무시하고 본다. */
    /** 확인할 조건을 말하는 꼴(공백을 뺀 글에서 찾는다). 그 조건은 규칙 문장이 판정 이유 글 그대로 붙인다. */
    private static final Pattern CHECK_TALK = Pattern.compile("확인(해야|할것|할필요|이필요|하세요|해보|하길|이요)");
    static final List<String> OVERREACH = List.of("전 과정", "전반", "전체 과정", "모든 과정", "처음부터 끝까지", "총괄");

    private final EligibilityService eligibility;
    private final RecommendService recommend;
    private final RecommendRepository repository;
    private final RoundService rounds;
    private final ReasonWriter writer;
    private final ReasonLimiter limiter;
    private final ReasonProperties props;
    private final String systemPrompt;
    private final String promptVersion;
    private final Map<String, String> cache;

    public ReasonService(EligibilityService eligibility, RecommendService recommend, RecommendRepository repository,
                         RoundService rounds, ReasonWriter writer, ReasonLimiter limiter, ReasonProperties props) {
        this.eligibility = eligibility;
        this.recommend = recommend;
        this.repository = repository;
        this.rounds = rounds;
        this.writer = writer;
        this.limiter = limiter;
        this.props = props;
        this.systemPrompt = load("prompts/recommendation-reason-system.md");
        this.promptVersion = sha256(systemPrompt).substring(0, 12);
        int max = Math.max(1, props.cacheSize());
        this.cache = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                return size() > max;
            }
        };
    }

    public RecommendationReason reason(AuthUser user, String ip, long jobId, ProfileInput profile) {
        var round = rounds.current();
        LocalDate asOf = round.replay().defaultAsOf();
        List<Judged> judged = eligibility.judgeAll(round.id(), profile);
        Judged target = judged.stream().filter(j -> j.requirement().jobId() == jobId).findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.JOB_NOT_FOUND, "없는 직무예요"));
        EligibilityJob job = target.result();
        if (job.verdict() == Verdict.INELIGIBLE) {
            return new RecommendationReason(job.jobId(), ReasonSource.TEMPLATE, INELIGIBLE_TEXT,
                    recommend.citations(round.id(), profile, job.jobId(), job.institution().id()));
        }
        Explained explained = recommend.explain(round.id(), judged, profile, asOf, job.jobId()).orElseThrow();
        Scored scored = explained.scored();
        List<Citation> citations = explained.citations();
        String template = explained.template();

        String key = sha256(String.join("\u0001", promptVersion, props.model(), Long.toString(jobId),
                Integer.toString(profile.departmentId()), Integer.toString(profile.grade()),
                String.valueOf(profile.interestText()), job.verdict().name(), scored.fit().name()));
        synchronized (cache) {
            String hit = cache.get(key);
            if (hit != null) {
                return new RecommendationReason(job.jobId(), ReasonSource.CACHE, finish(hit, job, explained), citations);
            }
        }
        JobFacts facts = repository.facts(job.jobId()).orElseThrow();
        if (!limiter.tryAcquire(user.id(), ip)) {
            return new RecommendationReason(job.jobId(), ReasonSource.TEMPLATE, template, citations);
        }
        String department = target.result().reasons().stream()
                .filter(r -> r.layer() == Layer.MAJOR).map(ReasonLine::mine).findFirst().orElse("");
        Optional<String> text = writer.write(systemPrompt, userMessage(profile, department, job, facts, citations))
                .flatMap(d -> validate(d, citations, facts, job.majorMatch(), department, profile.interestText()));
        if (text.isEmpty()) {
            return new RecommendationReason(job.jobId(), ReasonSource.TEMPLATE, template, citations);
        }
        synchronized (cache) {
            cache.put(key, text.get());
        }
        return new RecommendationReason(job.jobId(), ReasonSource.LLM, finish(text.get(), job, explained), citations);
    }

    /**
     * 검증을 통과한 LLM 문장 뒤에 규칙 문장을 붙인다(ADR-0020): 선호 전공에 소속 학과가 있는지(늘 — LLM은 선호 전공을
     * 말하지 않는다), 추천 안 같은 팀 직무와 요건이 다르면 그 차이, '확인 필요'면 확인할 조건. 모두 기본 문장과 같은 글이다.
     */
    static String finish(String text, EligibilityJob job, Explained explained) {
        StringBuilder b = new StringBuilder(text);
        if (explained.majorSentence() != null) {
            b.append(' ').append(explained.majorSentence());
        }
        if (explained.contrast() != null) {
            b.append(' ').append(explained.contrast());
        }
        if (explained.checkSentence() != null) {
            b.append(' ').append(explained.checkSentence());
        }
        return b.toString();
    }

    /** 앞의 두 검증 없이 원문 대조까지만(단위 테스트용). */
    static Optional<String> validate(ReasonDraft draft, List<Citation> citations, JobFacts facts) {
        return validate(draft, citations, facts, MajorMatch.MATCH, "", null);
    }

    /**
     * LLM 초안 검증. 통과하면 화면에 낼 문장(공백 정리), 아니면 빈 값. 원문 대조에 더해(ADR-0020)
     * <ul>
     *   <li>선호 전공에 소속 학과가 없으면 학과 이름(예: 메이크업디자인)을 직무와 잇지 않는다 — 문장에 나오면 탈락</li>
     *   <li>관심 분야 낱말을 기관·업종에 붙이지 않는다: 직무·기관 원문에 없는 관심 낱말 바로 뒤에
     *       '브랜드·기업·회사·업계·업종·시장·산업'이 오면 탈락(의류 회사를 '뷰티 브랜드'라고 부르는 경우)</li>
     *   <li>'선호 전공'·확인할 조건을 말한 문장, 원문보다 넓히는 말('전 과정'·'전반'·'모든 과정' 등 — 근거·직무 원문에
     *       그 말이 없을 때)이 든 문장은 뺀다. 선호 전공·확인할 조건은 규칙 문장이 뒤에 붙인다({@link #finish}).
     *       남는 문장이 없으면 탈락</li>
     * </ul>
     */
    static Optional<String> validate(ReasonDraft draft, List<Citation> citations, JobFacts facts, MajorMatch major,
                                     String department, String interestText) {
        if (draft == null || draft.text() == null) {
            return Optional.empty();
        }
        Set<Long> seen = new HashSet<>();
        for (Long n : draft.citationNumbers() == null ? List.<Long>of() : draft.citationNumbers()) {
            if (n == null || n < 1 || n > citations.size() || !seen.add(n)) {
                return Optional.empty();
            }
        }
        List<String> sources = new ArrayList<>();
        citations.forEach(c -> sources.add(c.quote()));
        sources.add(facts.overview());
        sources.add(facts.competencies());
        sources.add(facts.educationGoal());
        sources.add(facts.title());
        sources.add(facts.team());
        sources.add(facts.institution());
        sources.add(facts.weeklyPlan());
        List<String> withIndustry = new ArrayList<>(sources);
        withIndustry.add(facts.businessType());
        withIndustry.add(facts.businessItem());
        String known = withIndustry.stream().filter(x -> x != null).map(ReasonService::squash).reduce("", String::concat);

        // 규칙 문장이 맡는 말('선호 전공')·원문보다 넓히는 말이 든 문장은 뺀다(남은 문장은 고치지 않는다)
        String text = sentences(draft.text().replace("*", "").strip().replaceAll("\\s+", " ")).stream()
                .filter(s -> keep(s, known))
                .collect(Collectors.joining(" "));
        if (text.length() < MIN_LENGTH || text.length() > MAX_LENGTH) {
            return Optional.empty();
        }
        // 화면 문체(해요체)와 호칭 규칙. 어기면 기본 문장으로 바꾼다
        if (!(text.endsWith("요.") || text.endsWith("요")) || text.contains("습니다") || text.contains("당신")) {
            return Optional.empty();
        }
        Matcher m = QUOTED.matcher(text);
        while (m.find()) {
            String quote = squash(m.group(1));
            boolean found = sources.stream().anyMatch(s -> s != null && squash(s).contains(quote));
            if (!found) {
                return Optional.empty();
            }
        }
        if (major == MajorMatch.NOT_LISTED) {
            String stem = DEPARTMENT_SUFFIX.matcher(department == null ? "" : department.strip()).replaceFirst("");
            if (stem.length() >= 2 && squash(text).contains(squash(stem))) {
                return Optional.empty();
            }
        }
        for (String word : interestWords(interestText)) {
            if (!known.contains(word)
                    && Pattern.compile(Pattern.quote(word) + "\\s*" + INDUSTRY_NOUNS).matcher(text).find()) {
                return Optional.empty();
            }
        }
        return Optional.of(text);
    }

    /** 해요체 문장 단위('요.' 뒤 공백에서 나눈다). */
    static List<String> sentences(String text) {
        return List.of(text.split("(?<=요\\.)\\s+"));
    }

    /** 이 문장을 남길지: '선호 전공'·확인할 조건을 말하지 않고(규칙 문장이 맡는다), 원문에 없는 넓히는 말이 없다. */
    private static boolean keep(String sentence, String known) {
        String s = squash(sentence);
        if (s.contains("선호전공") || CHECK_TALK.matcher(s).find()) {
            return false;
        }
        for (String phrase : OVERREACH) {
            String p = squash(phrase);
            if (s.contains(p) && !known.contains(p)) {
                return false;
            }
        }
        return true;
    }

    /** 관심 문장의 낱말(2글자 이상, 끝 조사 하나는 뗀다). */
    static List<String> interestWords(String interestText) {
        if (interestText == null || interestText.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String w : interestText.strip().split("[\\s,·/]+")) {
            String t = w.length() > 2 ? TRAILING_PARTICLE.matcher(w).replaceFirst("") : w;
            if (t.length() >= 2) {
                out.add(t);
            }
        }
        return out;
    }

    static String userMessage(ProfileInput profile, String department, EligibilityJob job, JobFacts facts,
                              List<Citation> citations) {
        StringBuilder b = new StringBuilder();
        b.append("[학생]\n");
        b.append("학과: ").append(department).append('\n');
        b.append("학년: ").append(profile.grade()).append("학년\n");
        String interest = profile.interestText();
        b.append("관심 분야: ").append(interest == null || interest.isBlank() ? "적지 않음" : interest.strip()).append("\n\n");

        b.append("[직무]\n");
        b.append("기관: ").append(facts.institution()).append('\n');
        b.append("기관 업종: ").append(orDash(facts.businessType())).append(" / ").append(orDash(facts.businessItem()))
                .append('\n');
        b.append("직무: ").append(facts.title()).append(" (부서: ").append(facts.team()).append(")\n");
        b.append("유형: ").append(CodeLabels.label("jobType", facts.jobType())).append('\n');
        b.append("직무 개요: ").append(cut(facts.overview())).append('\n');
        b.append("교육 목표: ").append(cut(facts.educationGoal())).append('\n');
        b.append("요구 역량: ").append(cut(facts.competencies())).append('\n');
        b.append("주차 계획: ").append(cut(facts.weeklyPlan())).append('\n');
        b.append("선호 전공: ").append(facts.majorText() == null ? "—" : facts.majorText())
                .append(" (내 학과 ").append(switch (job.majorMatch()) {
                    case MATCH -> "포함";
                    case OPEN -> "— 전공 무관";
                    case NOT_LISTED -> "미포함 — 선호 전공은 참고 사항이라 지원은 할 수 있음";
                }).append(") — 이 사실은 문장 뒤에 따로 붙으니 쓰지 않음\n");
        b.append("판정: ").append(CodeLabels.label("verdict", job.verdict().name()));
        List<String> checks = job.reasons().stream()
                .filter(r -> r.layer() == Layer.INSTITUTION && (r.result() == Result.NOT_MET || r.result() == Result.CHECK))
                .map(r -> r.item() + " " + r.requirement()).toList();
        if (!checks.isEmpty()) {
            b.append(" (확인할 것: ").append(String.join(", ", checks)).append(") — 이 조건은 문장 뒤에 따로 붙으니 쓰지 않음");
        }
        b.append("\n\n[근거]\n");
        if (citations.isEmpty()) {
            b.append("없음\n");
        }
        for (int i = 0; i < citations.size(); i++) {
            Citation c = citations.get(i);
            b.append('[').append(i + 1).append("] ").append(c.documentTitle()).append(' ').append(c.page())
                    .append("쪽: \"").append(c.quote()).append("\"\n");
        }
        return b.toString();
    }

    private static String orDash(String s) {
        return s == null || s.isBlank() ? "—" : s.strip();
    }

    private static String cut(String s) {
        if (s == null || s.isBlank()) {
            return "—";
        }
        String t = s.strip().replaceAll("\\s+", " ");
        return t.length() <= FACT_LIMIT ? t : t.substring(0, FACT_LIMIT) + "…";
    }

    private static String squash(String s) {
        return s.replaceAll("\\s+", "");
    }

    private static String load(String path) {
        try {
            return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
