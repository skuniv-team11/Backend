package kr.ac.skuniv.coopradar.career;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import kr.ac.skuniv.coopradar.auth.AuthUser;
import kr.ac.skuniv.coopradar.career.CareerDrafts.CoveredDraft;
import kr.ac.skuniv.coopradar.career.CareerDrafts.UnitsDraft;
import kr.ac.skuniv.coopradar.career.CareerDtos.Covered;
import kr.ac.skuniv.coopradar.career.CareerDtos.Expand;
import kr.ac.skuniv.coopradar.career.CareerDtos.Input;
import kr.ac.skuniv.coopradar.career.CareerDtos.JobCareer;
import kr.ac.skuniv.coopradar.career.CareerDtos.Linked;
import kr.ac.skuniv.coopradar.career.CareerDtos.Ncs;
import kr.ac.skuniv.coopradar.career.CareerDtos.NextLevel;
import kr.ac.skuniv.coopradar.career.CareerDtos.Report;
import kr.ac.skuniv.coopradar.career.CareerDtos.ReportNcs;
import kr.ac.skuniv.coopradar.career.CareerDtos.ReportPath;
import kr.ac.skuniv.coopradar.career.CareerDtos.Source;
import kr.ac.skuniv.coopradar.career.CareerDtos.Unit;
import kr.ac.skuniv.coopradar.career.CareerDtos.UnitRef;
import kr.ac.skuniv.coopradar.career.CareerRepository.JobNcs;
import kr.ac.skuniv.coopradar.career.CareerRepository.Link;
import kr.ac.skuniv.coopradar.career.CareerRepository.Saved;
import kr.ac.skuniv.coopradar.career.CareerRepository.Stored;
import kr.ac.skuniv.coopradar.common.ApiException;
import kr.ac.skuniv.coopradar.common.ErrorCode;
import kr.ac.skuniv.coopradar.explore.ExploreDtos.Fallback;
import kr.ac.skuniv.coopradar.explore.ExploreLimiter;
import kr.ac.skuniv.coopradar.explore.ExploreText;
import kr.ac.skuniv.coopradar.explore.ExploreVerifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/**
 * 실습 뒤 커리어(#33~#36, ADR-0032). 직무의 NCS 세분류·능력단위·넓혀 갈 직무·직업은 시드에서 읽고(실행 중 공공 API 없음),
 * 커리어 리포트는 AI가 실습 내용에서 다룬 능력단위를 고르면 서버가 구절을 원문과 대조해 통과한 것만 둔다.
 * <ul>
 *   <li>AI에는 실습 내용(가린 뒤)과 그 세분류 능력단위 이름·정의만 보낸다. 학과·학년·평점은 보내지 않는다</li>
 *   <li>대조: 목록 안의 단위(개정 표기만 틀리면 단위 번호로 찾는다) · 한 번만 · 학생 구절이 실습 내용 한 문장(줄) 안에 그대로(4~100자, 띄어쓰기·따옴표 무시) ·
 *       이유는 해요체 10~150자('습니다'·'당신'·'선호 전공' 없음, 직무 탐색과 같은 규칙)</li>
 *   <li>키 없음·한도(직무 탐색과 같은 한도)·실패·통과 0개면 AI 없이 능력단위 목록만(source NONE)</li>
 *   <li>넓혀 갈 세분류는 채운 단위와 이어진 수(능력단위끼리 연결표)가 많은 순으로, 같은 세분류는 한 단계 위(nextLevel)로 보여 준다</li>
 * </ul>
 */
@Service
public class CareerService {

    static final int QUOTE_MIN = 4;
    static final int PRACTICE_MIN = 100;
    static final int QUOTE_MAX = 100;
    static final int MORE_MAX = 3;
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?。])\\s+");

    private final CareerRepository repository;
    private final CareerWriter writer;
    private final ExploreLimiter limiter;
    private final String systemPrompt;
    private final String promptVersion;

    public CareerService(CareerRepository repository, CareerWriter writer, ExploreLimiter limiter) {
        this.repository = repository;
        this.writer = writer;
        this.limiter = limiter;
        this.systemPrompt = load("prompts/career-units-system.md");
        this.promptVersion = sha256(systemPrompt).substring(0, 12);
    }

    /** #33 직무의 커리어 길. */
    public JobCareer path(int jobId) {
        JobNcs job = repository.job(jobId).orElseThrow(() -> new ApiException(ErrorCode.JOB_NOT_FOUND, "없는 직무예요"));
        if (job.subcategory() == null) {
            return new JobCareer(jobId, null, List.of(), List.of());
        }
        String code = job.subcategory().code();
        return new JobCareer(jobId, new Ncs(code, job.subcategory().name(), job.subcategory().path(), job.note(),
                repository.units(code)), repository.expand(code), repository.occupations(code));
    }

    /** #34 커리어 리포트 만들기. */
    public Report create(AuthUser user, String ip, CareerReportRequest req) {
        if (!Boolean.TRUE.equals(req.consent())) {
            throw new ApiException(ErrorCode.CONSENT_REQUIRED, "실습 내용을 AI 정리에 쓰고 리포트와 함께 저장하는 데 동의해 주세요");
        }
        JobNcs job = repository.job(req.jobId())
                .orElseThrow(() -> new ApiException(ErrorCode.JOB_NOT_FOUND, "없는 직무예요"));
        if (job.subcategory() == null) {
            throw ApiException.invalid("jobId", "NCS 세분류가 정해지지 않은 직무예요");
        }
        String text = ExploreText.maskLines(req.practiceText()); // 줄바꿈을 남겨야 '한 줄 안' 대조가 된다
        if (text.length() < PRACTICE_MIN) {
            throw ApiException.invalid("practiceText", "가린 뒤 " + PRACTICE_MIN + "자 이상이어야 해요");
        }
        List<Unit> units = repository.units(job.subcategory().code());
        Map<String, String[]> covered = new LinkedHashMap<>();
        Fallback fallback = null;
        if (!writer.available()) {
            fallback = Fallback.NO_KEY;
        } else if (!limiter.tryAcquire(user.id(), ip)) {
            fallback = Fallback.LIMITED;
        } else {
            Optional<UnitsDraft> draft = writer.units(systemPrompt, userMessage(job, units, text));
            if (draft.isEmpty()) {
                fallback = Fallback.AI_ERROR;
            } else {
                covered = verify(draft.get(), units, text);
                if (covered.isEmpty()) {
                    fallback = Fallback.VERIFY_FAILED;
                }
            }
        }
        Source source = fallback == null ? Source.AI : Source.NONE;
        Saved saved = repository.replace(user.id(), job.jobId(), job.subcategory().code(), text, source, fallback,
                source == Source.AI ? writer.model() : null, promptVersion, covered);
        return view(new Stored(saved.id(), job.jobId(), job.subcategory().code(), text, source, fallback,
                saved.createdAt(), covered), job);
    }

    /** #35 */
    public Report mine(AuthUser user) {
        Stored s = repository.find(user.id())
                .orElseThrow(() -> new ApiException(ErrorCode.CAREER_REPORT_NOT_FOUND, "저장된 커리어 리포트가 없어요"));
        JobNcs job = repository.job(s.jobId()).orElseThrow();
        return view(s, job);
    }

    /** #36 */
    public void delete(AuthUser user) {
        repository.delete(user.id());
    }

    private Report view(Stored s, JobNcs job) {
        String code = s.subcategory();
        List<Unit> units = repository.units(code);
        List<Covered> covered = new ArrayList<>();
        List<UnitRef> notCovered = new ArrayList<>();
        for (Unit u : units) {
            String[] c = s.covered().get(u.code());
            if (c != null) {
                covered.add(new Covered(u.code(), u.name(), u.level(), c[0], c[1]));
            } else {
                notCovered.add(new UnitRef(u.code(), u.name(), u.level()));
            }
        }
        var sub = repository.subcategory(code).orElseThrow(); // 세분류가 시드에서 빠지면 리포트도 cascade로 지워진다
        Set<String> done = s.covered().keySet();
        return new Report(s.id(), s.createdAt(), job.jobId(), job.title(), job.team(), job.institution(), s.source(),
                s.fallback(), new Input(s.practiceText()), new ReportNcs(code, sub.name(), sub.path(), units.size()), covered,
                notCovered, nextLevel(units, done), paths(repository.expand(code), repository.links(code), done,
                        repository::units), repository.occupations(code));
    }

    /**
     * 넓혀 갈 세분류마다 채운 단위와 이어진 단위·더 채울 것. 이어진 수가 많은 순, 같으면 사람이 고른 순위 순.
     * 이어진 단위는 연결표(능력단위끼리, AI 초안 + 사람 확인)에서 출발 단위가 채운 단위인 것만.
     */
    static List<ReportPath> paths(List<Expand> expand, List<Link> links, Set<String> done,
                                  Function<String, List<Unit>> unitsOf) {
        List<ReportPath> out = new ArrayList<>();
        for (Expand e : expand) {
            List<Linked> linked = links.stream()
                    .filter(l -> l.toCode().equals(e.code()) && done.contains(l.from().code()))
                    .map(l -> new Linked(l.to().code(), l.to().name(), l.to().level(), ref(l.from()), l.note(), l.checked()))
                    .toList();
            Set<String> linkedCodes = linked.stream().map(Linked::code).collect(Collectors.toSet());
            List<UnitRef> more = unitsOf.apply(e.code()).stream()
                    .filter(u -> !linkedCodes.contains(u.code()))
                    .sorted(Comparator.comparing((Unit u) -> u.level() == null ? Integer.MAX_VALUE : u.level()))
                    .limit(MORE_MAX)
                    .map(CareerService::ref)
                    .toList();
            out.add(new ReportPath(e.rank(), e.code(), e.name(), e.path(), e.relation(), e.unitCount(), linked.size(),
                    linked, more, e.occupations()));
        }
        out.sort(Comparator.comparingInt(ReportPath::linkedCount).reversed().thenComparingInt(ReportPath::rank));
        return out;
    }

    /**
     * 같은 세분류 한 단계 위: 채운 단위에 가장 많은 수준(같으면 낮은 쪽)에서 안 채운 것, 그다음 그보다 높은 수준 중 실제로 있는
     * 가장 낮은 수준의 것(수준이 건너뛰는 세분류가 있다 — 응용SW엔지니어링은 3 다음이 5).
     */
    static NextLevel nextLevel(List<Unit> units, Set<String> done) {
        Map<Integer, Long> counts = units.stream()
                .filter(u -> done.contains(u.code()) && u.level() != null)
                .collect(Collectors.groupingBy(Unit::level, Collectors.counting()));
        Integer base = counts.isEmpty()
                ? units.stream().map(Unit::level).filter(Objects::nonNull).min(Integer::compare).orElse(null)
                : counts.entrySet().stream()
                        .max(Map.Entry.<Integer, Long>comparingByValue()
                                .thenComparing(Map.Entry.<Integer, Long>comparingByKey().reversed()))
                        .orElseThrow().getKey();
        if (base == null) {
            return new NextLevel(null, List.of());
        }
        int from = base;
        Integer next = units.stream().map(Unit::level).filter(l -> l != null && l > from).min(Integer::compare)
                .orElse(null);
        List<UnitRef> pick = new ArrayList<>();
        for (Integer level : next == null ? List.of(base) : List.of(base, next)) {
            for (Unit u : units) {
                if (pick.size() < MORE_MAX && !done.contains(u.code()) && Objects.equals(u.level(), level)) {
                    pick.add(ref(u));
                }
            }
        }
        return new NextLevel(base, pick);
    }

    private static UnitRef ref(Unit u) {
        return new UnitRef(u.code(), u.name(), u.level());
    }

    /** 초안 → 통과한 단위(코드 → [학생 구절, 이유]). */
    static Map<String, String[]> verify(UnitsDraft draft, List<Unit> units, String text) {
        Map<String, String[]> out = new LinkedHashMap<>();
        if (draft == null || draft.covered() == null) {
            return out;
        }
        List<String> pieces = pieces(text);
        for (CoveredDraft c : draft.covered()) {
            String code = c == null ? null : resolve(c.unitCode(), units);
            if (code == null || out.containsKey(code)) {
                continue;
            }
            Optional<String> reason = ExploreVerifier.sentence(c.reason());
            String quote = c.studentQuote() == null ? "" : c.studentQuote().strip();
            if (reason.isEmpty() || quote.length() < QUOTE_MIN || quote.length() > QUOTE_MAX
                    || quote.contains(ExploreText.MASK)) {
                continue;
            }
            String key = ExploreText.squashQuote(quote);
            if (key.length() < ExploreText.KEY_MIN) {
                continue;
            }
            // 화면에는 AI 문자열이 아니라 실습 내용 원문 구간을 낸다
            pieces.stream().map(p -> ExploreText.slice(p, key)).flatMap(Optional::stream).findFirst()
                    .ifPresent(source -> out.put(code, new String[] {source, reason.get()}));
        }
        return out;
    }

    /**
     * AI가 낸 단위 코드 → 목록의 코드. 같으면 그대로, 개정 표기(_21v4 · _21v5)만 다르면 앞 10자리(세분류 + 단위 번호)로 찾는다 —
     * 세분류 안에서 단위 번호는 하나뿐이다(2026-10-10 실제 호출에서 판 번호만 틀린 경우가 있었다). 없으면 null.
     */
    static String resolve(String draftCode, List<Unit> units) {
        if (draftCode == null) {
            return null;
        }
        String c = draftCode.strip();
        for (Unit u : units) {
            if (u.code().equals(c)) {
                return u.code();
            }
        }
        if (c.length() < 10 || !c.substring(0, 10).chars().allMatch(Character::isDigit)) {
            return null;
        }
        List<String> same = units.stream().map(Unit::code).filter(u -> u.startsWith(c.substring(0, 10))).toList();
        return same.size() == 1 ? same.get(0) : null;
    }

    /** 실습 내용의 문장(줄을 나누고, 마침표·물음표·느낌표 뒤에서 나눈다). */
    static List<String> pieces(String text) {
        List<String> out = new ArrayList<>();
        for (String line : text.split("\\R")) {
            for (String s : SENTENCE_END.split(line)) {
                if (!s.isBlank()) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    static String userMessage(JobNcs job, List<Unit> units, String text) {
        StringBuilder b = new StringBuilder("# 실습 내용\n").append(text).append("\n\n");
        b.append("# 이 직무의 NCS 능력단위 — ").append(job.subcategory().name()).append(" (")
                .append(String.join(" > ", job.subcategory().path())).append(")\n");
        for (Unit u : units) {
            b.append("- ").append(u.code()).append(" ").append(u.name()).append(" (수준 ").append(u.level()).append("): ")
                    .append(u.definition() == null ? "—" : u.definition()).append('\n');
        }
        return b.toString();
    }

    private static String load(String path) {
        try {
            return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
