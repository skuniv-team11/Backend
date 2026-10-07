package kr.ac.skuniv.coopradar.recommend;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import kr.ac.skuniv.coopradar.eligibility.EligibilityDtos.MajorMatch;
import kr.ac.skuniv.coopradar.eligibility.EligibilityDtos.Verdict;
import kr.ac.skuniv.coopradar.eligibility.EligibilityRules;
import kr.ac.skuniv.coopradar.eligibility.EligibilityService;
import kr.ac.skuniv.coopradar.eligibility.EligibilityService.Judged;
import kr.ac.skuniv.coopradar.eligibility.ProfileInput;
import kr.ac.skuniv.coopradar.job.RoundRef;
import kr.ac.skuniv.coopradar.recommend.EvidencePicker.Picked;
import kr.ac.skuniv.coopradar.recommend.EvidencePicker.Testimonial;
import kr.ac.skuniv.coopradar.recommend.EvidenceText.JobText;
import kr.ac.skuniv.coopradar.recommend.FitScorer.Scored;
import kr.ac.skuniv.coopradar.recommend.RecommendDtos.Blocked;
import kr.ac.skuniv.coopradar.recommend.RecommendDtos.Citation;
import kr.ac.skuniv.coopradar.recommend.RecommendDtos.Recommendation;
import kr.ac.skuniv.coopradar.recommend.RecommendDtos.Recommendations;
import kr.ac.skuniv.coopradar.reference.RoundService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 적합도 추천(#15, ADR-0018). 판정에서 지원 불가가 아니고 기준일에 마감되지 않은 직무(ADR-0016) 중
 * 추천할 이유가 있는 직무(선호 전공이 맞거나 관심 문장과 겹침, ADR-0022)를 규칙+키워드 점수로 줄 세워 상위 5개까지 준다.
 * 근거 인용과 기본 이유 문장은 관심 문장과 겹치는 원문을 골라 만든다(ADR-0020, {@link EvidencePicker}·{@link ReasonTemplates}).
 * 실행 중 외부 호출이 없다. LLM 이유 문장은 #16이 따로 만든다.
 * 지망 점검(#23)도 같은 점수로 빈 자리의 적합도를 정한다.
 */
@Service
public class RecommendService {

    static final int LIMIT = 5;
    static final String REASON_PENDING = "PENDING";
    /** 추천 0개일 때 blockedBy 항목: 선호 전공도 관심 문장도 맞지 않아 뺀 직무(ADR-0022). */
    static final String UNRELATED = "관심 분야";

    private final EligibilityService eligibility;
    private final RecommendRepository repository;
    private final RoundService rounds;

    public RecommendService(EligibilityService eligibility, RecommendRepository repository, RoundService rounds) {
        this.eligibility = eligibility;
        this.repository = repository;
        this.rounds = rounds;
    }

    /**
     * 직무 하나의 추천 설명: 점수, 근거 인용(수기 → 운영계획서), 기본 이유 문장.
     *
     * @param matched  관심 문장과 겹쳐 고른 계획서 조각(없으면 null). 이유 문장 프롬프트에도 알려 준다
     * @param contrast 추천 안의 같은 팀 다른 직무와 요건이 다른 점(없으면 null). 기본 문장 끝에 이미 들어 있다
     * @param lead     판정 한 줄(내 값으로 갖춘 조건·챙길 것, ADR-0024). 기본 문장의 맨 앞이고 LLM 문장 앞에도 붙는다
     * @param major    내 학과와 선호 전공 한 문장. 기본 문장의 두 번째이고 LLM 문장 앞에도 붙는다
     * @param interestNote 관심 분야가 조금·적게 겹친다는 한 문장(많이 겹치거나 관심을 안 적었으면 null). LLM 문장 앞에도 붙는다
     */
    record Explained(Scored scored, List<Citation> citations, String template, EvidenceText.Segment matched,
                     String contrast, String lead, String major, String interestNote) {
    }

    @Transactional(readOnly = true)
    public Recommendations recommend(ProfileInput profile) {
        var round = rounds.current();
        LocalDate asOf = round.replay().defaultAsOf(); // 리플레이 중 기준일(운영 때는 오늘 — ADR-0016)
        List<Judged> judged = eligibility.judgeAll(round.id(), profile);
        List<Explained> top = top(round.id(), judged, profile, asOf);
        List<Recommendation> items = new ArrayList<>();
        for (Explained e : top) {
            var r = e.scored().judged().result();
            items.add(new Recommendation(items.size() + 1, r.jobId(), r.title(), r.institution(), r.verdict(),
                    e.scored().fit(), e.scored().features().jobType(), e.scored().features().stipend(), e.template(),
                    REASON_PENDING, e.citations()));
        }
        return new Recommendations(new RoundRef(round.id(), round.termCode()), items,
                items.isEmpty() ? blockedBy(judged, score(round.id(), judged, profile), asOf) : List.of());
    }

    /** 추천 상위 5개의 설명. */
    @Transactional(readOnly = true)
    public List<Explained> top(int roundId, List<Judged> judged, ProfileInput profile, LocalDate asOf) {
        Context ctx = context(roundId, profile);
        List<Scored> top = recommendable(score(roundId, judged, profile), asOf);
        return top.stream().map(s -> explain(ctx, s, top)).toList();
    }

    /**
     * 직무 하나의 설명(이유 문장 #16). 추천 상위 5개 안이면 같은 팀 직무와의 차이도 붙는다.
     * 지원 불가라 점수가 없는 직무는 빈 값.
     */
    @Transactional(readOnly = true)
    public Optional<Explained> explain(int roundId, List<Judged> judged, ProfileInput profile, LocalDate asOf, int jobId) {
        Context ctx = context(roundId, profile);
        List<Scored> all = score(roundId, judged, profile);
        List<Scored> top = recommendable(all, asOf);
        return all.stream().filter(s -> s.jobId() == jobId).findFirst()
                .map(s -> explain(ctx, s, top.contains(s) ? top : List.of()));
    }

    /** 점수 없이 근거 인용만(지원 불가 직무의 이유 문장 #16). */
    @Transactional(readOnly = true)
    public List<Citation> citations(int roundId, ProfileInput profile, int jobId, int institutionId) {
        Context ctx = context(roundId, profile);
        JobText text = ctx.texts().get(jobId);
        return text == null ? List.of()
                : ctx.picker().pick(text, ctx.testimonials().getOrDefault(institutionId, List.of())).citations();
    }

    /** 추천 목록: 기준일에 마감되지 않았고 추천할 이유가 있는 직무 중 점수 상위 5개까지(ADR-0022). */
    static List<Scored> recommendable(List<Scored> scored, LocalDate asOf) {
        return scored.stream().filter(s -> !closedOn(s.judged(), asOf)).filter(Scored::relevant).limit(LIMIT).toList();
    }

    /** 기준일에 마감됐는지(closesOn ≤ 기준일, closesOn = 이 날부터 지원 불가). */
    static boolean closedOn(Judged judged, LocalDate asOf) {
        return EligibilityService.closedOn(judged, asOf);
    }

    /** 지원 불가가 아닌 직무 전부의 점수(높은 순). 지망 점검이 빈 자리 적합도에 쓴다. 관심 유사도는 회차 직무 전부로 잰다. */
    @Transactional(readOnly = true)
    public List<Scored> score(int roundId, List<Judged> judged, ProfileInput profile) {
        return FitScorer.score(judged, repository.features(roundId), profile.interestText(), profile.departmentId());
    }

    /** 근거를 고르는 데 필요한 것을 한 번에 읽는다(요청마다 — 직무 40개라 가볍다). */
    /**
     * @param aliases 직무별 선호 전공 표기(가리키는 학과가 적은 것 먼저)
     * @param departmentId 학생 학과
     * @param near   학생 학과와 같은 묶음의 가까운 학과(ADR-0028)
     */
    private record Context(Map<Integer, JobText> texts, Map<Integer, List<Testimonial>> testimonials,
                           Map<Integer, List<RecommendRepository.Alias>> aliases, int departmentId, Set<Integer> near,
                           Map<String, String> certificateLabels, EvidencePicker picker) {
    }

    private Context context(int roundId, ProfileInput profile) {
        Map<Integer, JobText> texts = repository.jobTexts(roundId).stream()
                .collect(Collectors.toMap(JobText::jobId, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        Map<Integer, List<Testimonial>> testimonials = repository.testimonials(roundId);
        List<Testimonial> allTestimonials = testimonials.values().stream().flatMap(List::stream).toList();
        return new Context(texts, testimonials, repository.majorAliases(roundId), profile.departmentId(),
                eligibility.nearDepartments(profile.departmentId()), repository.certificateLabels(),
                new EvidencePicker(texts.values(), allTestimonials, profile.interestText()));
    }

    private static Explained explain(Context ctx, Scored s, List<Scored> top) {
        var r = s.judged().result();
        JobText text = ctx.texts().get(r.jobId());
        Picked picked = text == null ? new Picked(List.of(), null)
                : ctx.picker().pick(text, ctx.testimonials().getOrDefault(r.institution().id(), List.of()));
        String contrast = contrast(ctx, s, top);
        boolean hiring = "HIRING".equals(s.features().jobType());
        String majorLabel = majorLabel(ctx, r.jobId(), r.majorMatch(), FitSentences.majorText(r));
        String lead = FitSentences.lead(s.judged(), ctx.certificateLabels());
        String major = FitSentences.major(r.verdict(), s.major(), FitSentences.department(r), majorLabel);
        String template = ReasonTemplates.build(lead, major, s.interestFit(), picked.matched(), hiring, contrast);
        return new Explained(s, picked.citations(), template, picked.matched(), contrast, lead, major,
                ReasonTemplates.interestNote(s.interestFit()));
    }

    /**
     * 전공 문장에 쓸 선호 전공 표기: 포함이면 내 학과가 든 가장 좁은 표기, 가까운 전공이면 가까운 학과가 든 가장 좁은 표기,
     * 밖이면 표기 요약('A·B 등' — 리스트에 적힌 순서), 전공 무관이면 없음.
     *
     * @param majorText 선호 전공 원문(리스트 칸). 요약의 표기 순서를 회사가 적은 순서로 맞춘다
     */
    static String majorLabel(Context ctx, int jobId, MajorMatch match, String majorText) {
        List<RecommendRepository.Alias> aliases = ctx.aliases().getOrDefault(jobId, List.of());
        return switch (match) {
            case MATCH -> aliases.stream().filter(a -> a.departments().contains(ctx.departmentId()))
                    .map(RecommendRepository.Alias::label).findFirst().orElse(null);
            case NEAR -> aliases.stream().filter(a -> a.departments().size() <= FitScorer.DIRECT_MAX_DEPARTMENTS)
                    .filter(a -> a.departments().stream().anyMatch(ctx.near()::contains))
                    .map(RecommendRepository.Alias::label).findFirst().orElse(null);
            case NOT_LISTED -> {
                String text = majorText == null ? "" : majorText;
                yield FitSentences.labelSummary(aliases.stream().map(RecommendRepository.Alias::label)
                        .sorted(Comparator.comparingInt(l -> text.indexOf(l) < 0 ? Integer.MAX_VALUE : text.indexOf(l)))
                        .toList());
            }
            case OPEN -> null;
        };
    }

    /** 추천 안에서 같은 기관·같은 팀인 다른 직무(순위가 앞선 것 먼저)와 요건이 다른 점. */
    private static String contrast(Context ctx, Scored s, List<Scored> top) {
        JobText mine = ctx.texts().get(s.jobId());
        if (mine == null) {
            return null;
        }
        for (Scored other : top) {
            JobText theirs = ctx.texts().get(other.jobId());
            if (other.jobId() == s.jobId() || theirs == null || theirs.institutionId() != mine.institutionId()
                    || !EvidenceText.sameTeam(theirs.team(), mine.team(), null)) {
                continue;
            }
            String c = ReasonTemplates.contrast(s.judged().requirement(), mine.competencies(),
                    other.judged().requirement(), theirs.competencies());
            if (c != null) {
                return c;
            }
        }
        return null;
    }

    /**
     * 추천이 0개일 때 막은 것: 지원 불가를 만든 항목별 직무 수(학교 규정 항목 · '자격증', ADR-0021)
     * + 지원 불가는 아니지만 기준일에 마감된 직무 수('모집 마감')
     * + 지원할 수 있고 마감 전이지만 선호 전공도 관심 문장도 맞지 않는 직무 수('관심 분야', ADR-0022).
     */
    static List<Blocked> blockedBy(List<Judged> judged, List<Scored> scored, LocalDate asOf) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Judged j : judged) {
            j.result().reasons().stream()
                    .filter(EligibilityRules::blocks)
                    .forEach(r -> counts.merge(r.item(), 1, Integer::sum));
            if (j.result().verdict() != Verdict.INELIGIBLE && closedOn(j, asOf)) {
                counts.merge("모집 마감", 1, Integer::sum);
            }
        }
        for (Scored s : scored) {
            if (!closedOn(s.judged(), asOf) && !s.relevant()) {
                counts.merge(UNRELATED, 1, Integer::sum);
            }
        }
        return counts.entrySet().stream().map(e -> new Blocked(e.getKey(), e.getValue())).toList();
    }
}
