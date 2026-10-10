package kr.ac.skuniv.coopradar.explore;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import kr.ac.skuniv.coopradar.explore.ExploreDrafts.PhraseDraft;
import kr.ac.skuniv.coopradar.explore.ExploreDrafts.PointDraft;
import kr.ac.skuniv.coopradar.explore.ExploreDrafts.RankDraft;
import kr.ac.skuniv.coopradar.explore.ExploreDrafts.RankedJob;
import kr.ac.skuniv.coopradar.explore.ExploreDrafts.WhyDraft;
import kr.ac.skuniv.coopradar.explore.ExploreDtos.JobPhrase;
import kr.ac.skuniv.coopradar.explore.ExploreDtos.Point;
import kr.ac.skuniv.coopradar.explore.ExploreDtos.WhyText;
import kr.ac.skuniv.coopradar.explore.ExploreText.Piece;
import kr.ac.skuniv.coopradar.explore.ExploreText.Student;

/**
 * AI 초안을 원문과 대조해 통과한 것만 남긴다(ADR-0031, AGENTS.md '검증 없이 내보내지 않는다').
 * <ul>
 *   <li>자리 번호: 후보(ELIGIBLE·NEEDS_CHECK, 마감 전) 안, 한 번만</li>
 *   <li>구절: 학생 구절은 학생 글 한 조각(경험·카드·관심 분야 하나) 안에, 직무 구절은 그 직무 원문 한 칸 안에 그대로
 *       (띄어쓰기·따옴표·가운뎃점·글머리표·끝 문장부호는 무시). 4~80자</li>
 *   <li>문장: 해요체로 끝남, '습니다'·'당신'·'선호 전공' 없음, 별표는 지움. 탐색 이유 10~150자, '왜 맞나요' 문장 10~150자</li>
 * </ul>
 */
public final class ExploreVerifier {

    static final int LIMIT = 5;
    static final int QUOTE_MIN = 4;
    static final int QUOTE_MAX = 80;
    static final int SENTENCE_MIN = 10;
    static final int SENTENCE_MAX = 150;

    private ExploreVerifier() {
    }

    /** 확인을 통과한 탐색 자리 하나. piece는 직무 구절이 든 칸. */
    record Ranked(int jobId, String studentQuote, String jobQuote, Piece piece, String reason) {
    }

    /** 순서 초안 → 통과한 자리(순서 그대로, 5개까지). */
    static List<Ranked> ranking(RankDraft draft, Set<Integer> candidates, Student student, Map<Integer, JobDoc> docs) {
        List<Ranked> out = new ArrayList<>();
        if (draft == null || draft.ranking() == null) {
            return out;
        }
        Set<Integer> seen = new HashSet<>();
        for (RankedJob r : draft.ranking()) {
            if (r == null || out.size() >= LIMIT) {
                continue;
            }
            int id = (int) r.id();
            if (r.id() != id || !candidates.contains(id) || !seen.add(id)) {
                continue;
            }
            JobDoc doc = docs.get(id);
            Optional<String> reason = sentence(r.reason());
            Optional<String> sq = studentQuote(r.studentQuote(), student);
            Optional<Piece> piece = jobPiece(r.jobQuote(), doc);
            if (doc == null || reason.isEmpty() || sq.isEmpty() || piece.isEmpty()) {
                continue;
            }
            out.add(new Ranked(id, sq.get(), r.jobQuote().strip(), piece.get(), reason.get()));
        }
        return out;
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
            if (text.isPresent() && sq.isPresent() && jobPiece(p.jobQuote(), doc).isPresent()) {
                points.add(new Point(text.get(), sq.get(), p.jobQuote().strip()));
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
        if (text.isEmpty() || jobPiece(p.jobQuote(), doc).isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new JobPhrase(text.get(), p.jobQuote().strip()));
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
        return student.pieces().stream().anyMatch(p -> ExploreText.squash(p).contains(q))
                ? Optional.of(quote.strip()) : Optional.empty();
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
        return q.length() < QUOTE_MIN - 1 ? null : q;
    }
}
