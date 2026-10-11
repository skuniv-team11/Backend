package kr.ac.skuniv.coopradar.explore;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import kr.ac.skuniv.coopradar.explore.ExploreDrafts.LinkDraft;
import kr.ac.skuniv.coopradar.explore.ExploreDrafts.LinkKind;
import kr.ac.skuniv.coopradar.explore.ExploreDrafts.LinkedJob;
import kr.ac.skuniv.coopradar.explore.ExploreDrafts.PhraseDraft;
import kr.ac.skuniv.coopradar.explore.ExploreDrafts.PointDraft;
import kr.ac.skuniv.coopradar.explore.ExploreDrafts.RankDraft;
import kr.ac.skuniv.coopradar.explore.ExploreDrafts.WhyDraft;
import kr.ac.skuniv.coopradar.explore.ExploreDtos.Basis;
import kr.ac.skuniv.coopradar.explore.ExploreDtos.Fit;
import kr.ac.skuniv.coopradar.explore.ExploreDtos.JobPhrase;
import kr.ac.skuniv.coopradar.explore.ExploreDtos.Point;
import kr.ac.skuniv.coopradar.explore.ExploreDtos.WhyText;
import kr.ac.skuniv.coopradar.explore.ExploreText.Piece;
import kr.ac.skuniv.coopradar.explore.ExploreText.Student;
import kr.ac.skuniv.coopradar.explore.ExploreText.StudentPiece;

/**
 * AI 초안을 원문과 대조해 통과한 것만 남긴다(ADR-0031, AGENTS.md '검증 없이 내보내지 않는다').
 * <ul>
 *   <li>자리 번호: 후보(ELIGIBLE·NEEDS_CHECK, 마감 전) 안. 같은 자리가 두 번 나오면 짝을 합친다</li>
 *   <li>짝의 종류(ADR-0038): 학생 구절이 해 본 일에 있으면 같은일 → SAME_TASK, 쓰이는능력 → TRANSFER(하고싶은일이라 하면
 *       버린다 — 해 본 일을 희망으로 적은 어긋난 짝). 고른 카드·관심 분야에 있으면 AI가 무엇이라 했든 WISH</li>
 *   <li>구절: 학생 구절은 학생 글 한 조각(경험·카드·관심 분야 하나) 안에, 직무 구절은 그 직무 원문 한 칸 안에 그대로
 *       (띄어쓰기·따옴표·가운뎃점·글머리표·끝 문장부호는 무시). 4~80자</li>
 *   <li>문장: 해요체로 끝남, '습니다'·'당신'·'선호 전공' 없음, 별표는 지움. 탐색 이유 10~150자, '왜 맞나요' 문장 10~150자</li>
 * </ul>
 */
public final class ExploreVerifier {

    /** 탐색 결과로 보여 주는 자리 수. */
    static final int LIMIT = 5;
    static final int QUOTE_MIN = 4;
    static final int QUOTE_MAX = 80;
    static final int SENTENCE_MIN = 10;
    static final int SENTENCE_MAX = 150;

    private ExploreVerifier() {
    }

    /** 탐색에서 AI가 볼 자리 수와 자리마다 볼 짝 수. */
    static final int MAX_JOBS = 10;
    static final int MAX_LINKS = 5;
    /** 자리 이유에 쓰지 않는 강도 표현(독립 검토 10/10: '바로·곧장' 과장). 있으면 규칙 문장으로 바꾼다. */
    private static final Pattern STRONG_WORDS = Pattern.compile("바로|곧장|그대로|완벽|딱 맞");
    /** 학생 구절 앞에 카드 자리 번호([105])를 옮겨 쓴 것. */
    private static final Pattern CARD_PREFIX = Pattern.compile("^\\s*\\[\\d+]\\s*");

    /** 대조를 통과한 짝. piece는 직무 구절이 든 칸. */
    record VerifiedLink(Fit kind, Basis basis, String studentQuote, String jobQuote, Piece piece) {
    }

    /** 대조를 통과한 짝이 하나 이상인 자리. reason은 규칙을 어겼으면 null(서비스가 단계 문장으로 바꾼다). */
    record VerifiedJob(int jobId, List<VerifiedLink> links, String reason) {
    }

    /**
     * @param jobs    통과한 자리(AI가 쓴 순서 — 순서는 서비스가 규칙으로 다시 정한다)
     * @param drafted AI가 낸 짝 수(0이면 AI가 이어지는 곳이 없다고 본 것, 0보다 큰데 jobs가 비면 대조 실패)
     */
    record Verified(List<VerifiedJob> jobs, int drafted) {
    }

    /** 탐색 초안 → 통과한 짝과 자리. */
    static Verified links(RankDraft draft, Set<Integer> candidates, Student student, Map<Integer, JobDoc> docs) {
        Map<Integer, List<VerifiedLink>> links = new LinkedHashMap<>();
        Map<Integer, String> reasons = new LinkedHashMap<>();
        Map<Integer, Set<String>> seen = new LinkedHashMap<>();
        int drafted = 0;
        if (draft == null || draft.jobs() == null) {
            return new Verified(List.of(), 0);
        }
        Set<Integer> looked = new HashSet<>();
        for (LinkedJob j : draft.jobs()) {
            if (j == null || j.links() == null) {
                continue;
            }
            drafted += j.links().size();
            int id = (int) j.id();
            if (j.id() != id || !candidates.contains(id) || docs.get(id) == null) {
                continue;
            }
            if (!looked.contains(id) && looked.size() >= MAX_JOBS) {
                continue;
            }
            looked.add(id);
            JobDoc doc = docs.get(id);
            for (LinkDraft l : j.links().subList(0, Math.min(MAX_LINKS, j.links().size()))) {
                verify(l, student, doc).ifPresent(v -> {
                    String key = v.kind() + "|" + ExploreText.squash(v.piece().text()) + "|" + ExploreText.squash(v.studentQuote());
                    if (seen.computeIfAbsent(id, k -> new HashSet<>()).add(key)) {
                        links.computeIfAbsent(id, k -> new ArrayList<>()).add(v);
                    }
                });
            }
            if (!reasons.containsKey(id)) {
                reasons.put(id, sentence(j.reason()).filter(r -> !STRONG_WORDS.matcher(r).find()).orElse(null));
            }
        }
        List<VerifiedJob> out = new ArrayList<>();
        links.forEach((id, ls) -> out.add(new VerifiedJob(id, List.copyOf(ls), reasons.get(id))));
        return new Verified(out, drafted);
    }

    /** 짝 하나: 학생 구절의 출처로 단계를 정하고, 직무 구절은 그 자리 원문 한 칸 안에 있어야 한다. */
    private static Optional<VerifiedLink> verify(LinkDraft l, Student student, JobDoc doc) {
        if (l == null || l.kind() == null) {
            return Optional.empty();
        }
        String raw = l.studentQuote() == null ? null : CARD_PREFIX.matcher(l.studentQuote()).replaceFirst("");
        String q = quoteKey(raw);
        if (q == null) {
            return Optional.empty();
        }
        Optional<StudentPiece> from = Optional.empty();
        Optional<String> sq = Optional.empty();
        for (StudentPiece p : student.typedPieces()) {
            sq = ExploreText.slice(p.text(), q);
            if (sq.isPresent()) {
                from = Optional.of(p);
                break;
            }
        }
        Optional<Piece> piece = jobPiece(l.jobQuote(), doc);
        Optional<String> jq = jobQuote(l.jobQuote(), doc);
        if (from.isEmpty() || piece.isEmpty() || jq.isEmpty()) {
            return Optional.empty();
        }
        Basis basis = from.get().basis();
        Fit kind;
        if (basis == Basis.EXPERIENCE) {
            if (l.kind() == LinkKind.하고싶은일) {
                return Optional.empty();
            }
            kind = l.kind() == LinkKind.같은일 ? Fit.SAME_TASK : Fit.TRANSFER;
        } else {
            kind = Fit.WISH;
        }
        return Optional.of(new VerifiedLink(kind, basis, sq.get(), jq.get(), piece.get()));
    }

    /** '왜 맞나요' 초안 → 통과한 것. summary가 통과하지 못하거나 points가 하나도 남지 않으면 빈 값. */
    static Optional<WhyText> why(WhyDraft draft, Student student, JobDoc doc) {
        if (draft == null) {
            return Optional.empty();
        }
        Optional<String> summary = sentence(draft.summary());
        List<Point> points = new ArrayList<>();
        for (PointDraft p : draft.points() == null ? List.<PointDraft>of() : draft.points()) {
            if (p == null || points.size() >= 3) {
                continue;
            }
            Optional<String> text = sentence(p.text());
            Optional<String> sq = studentQuote(p.studentQuote(), student);
            Optional<String> jq = jobQuote(p.jobQuote(), doc);
            if (text.isPresent() && sq.isPresent() && jq.isPresent()) {
                points.add(new Point(text.get(), sq.get(), jq.get()));
            }
        }
        if (summary.isEmpty() || points.isEmpty()) {
            return Optional.empty();
        }
        List<JobPhrase> tryNew = new ArrayList<>();
        for (PhraseDraft p : draft.tryNew() == null ? List.<PhraseDraft>of() : draft.tryNew()) {
            if (tryNew.size() < 2) {
                phrase(p, doc).ifPresent(tryNew::add);
            }
        }
        return Optional.of(new WhyText(summary.get(), points, tryNew, phrase(draft.prepare(), doc).orElse(null)));
    }

    private static Optional<JobPhrase> phrase(PhraseDraft p, JobDoc doc) {
        if (p == null) {
            return Optional.empty();
        }
        Optional<String> text = sentence(p.text());
        Optional<String> jq = jobQuote(p.jobQuote(), doc);
        if (text.isEmpty() || jq.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new JobPhrase(text.get(), jq.get()));
    }

    /** 화면에 낼 문장(공백 정리·별표 제거). 규칙을 어기면 빈 값. */
    public static Optional<String> sentence(String s) {
        if (s == null) {
            return Optional.empty();
        }
        String t = s.replace("*", "").strip().replaceAll("\\s+", " ");
        if (t.length() < SENTENCE_MIN || t.length() > SENTENCE_MAX) {
            return Optional.empty();
        }
        if (!(t.endsWith("요.") || t.endsWith("요")) || t.contains("습니다") || t.contains("당신")
                || ExploreText.squash(t).contains("선호전공")) {
            return Optional.empty();
        }
        return Optional.of(t);
    }

    static Optional<String> studentQuote(String quote, Student student) {
        String q = quoteKey(quote);
        if (q == null) {
            return Optional.empty();
        }
        return student.pieces().stream().map(p -> ExploreText.slice(p, q)).flatMap(Optional::stream).findFirst();
    }

    /** 직무 구절을 원문 그대로(칸 안에서 찾은 구간). */
    static Optional<String> jobQuote(String quote, JobDoc doc) {
        String q = quoteKey(quote);
        if (q == null || doc == null) {
            return Optional.empty();
        }
        return ExploreText.pieces(doc).stream().map(p -> ExploreText.slice(p.text(), q)).flatMap(Optional::stream)
                .findFirst();
    }

    static Optional<Piece> jobPiece(String quote, JobDoc doc) {
        String q = quoteKey(quote);
        if (q == null || doc == null) {
            return Optional.empty();
        }
        return ExploreText.pieces(doc).stream().filter(p -> ExploreText.squash(p.text()).contains(q)).findFirst();
    }

    /** 대조 열쇠. 길이가 맞지 않거나 가린 자리([가림])를 인용하면 null. */
    private static String quoteKey(String quote) {
        if (quote == null) {
            return null;
        }
        String t = quote.strip();
        if (t.length() < QUOTE_MIN || t.length() > QUOTE_MAX || t.contains(ExploreText.MASK)) {
            return null;
        }
        String q = ExploreText.squashQuote(t);
        return q.length() < ExploreText.KEY_MIN ? null : q;
    }
}
