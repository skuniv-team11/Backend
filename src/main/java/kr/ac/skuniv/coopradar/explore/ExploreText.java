package kr.ac.skuniv.coopradar.explore;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import kr.ac.skuniv.coopradar.recommend.EvidenceText;

/**
 * 직무 탐색 글 다루기(ADR-0031). AI 없이 규칙으로.
 * <ul>
 *   <li>학생 글 가리기: 이메일 · 전화번호 · 8~10자리 숫자(학번 등) → [가림]. AI에 보내기 전과 저장하기 전에</li>
 *   <li>학생 글·직무 원문을 AI에 보낼 모양으로(E7에서 시험한 모양)</li>
 *   <li>'하고 싶은 일' 카드: 직무 개요 항목(모자라면 주차 계획 항목)을 원문 그대로 짧게, 배우는 것·목표 항목은 빼고,
 *       기관 이름은 '회사'</li>
 *   <li>구절 대조용 조각: 학생 글은 경험·카드·관심 분야 하나씩, 직무는 칸(직무 개요·요구 역량·교육 목표의 줄·항목,
 *       주차 계획 한 항목, 부서, 직무명) 하나씩 — 구절은 한 조각 안에 있어야 한다</li>
 * </ul>
 */
public final class ExploreText {

    static final String MASK = "[가림]";
    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+(?:\\.[\\w-]+)+");
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)0\\d{1,2}[-.\\s]?\\d{3,4}[-.\\s]?\\d{4}(?!\\d)");
    private static final Pattern LONG_NUMBER = Pattern.compile("(?<!\\d)\\d{8,10}(?!\\d)");
    /** 대조할 때 무시하는 글자: 띄어쓰기·따옴표·가운뎃점·글머리표·줄표. */
    private static final Pattern NOISE = Pattern.compile("[\\s\"'“”‘’·•*\\-–—]+");
    private static final Pattern TRAILING_PUNCT = Pattern.compile("[.,!?~…]+$");
    private static final Pattern INLINE_BULLET = Pattern.compile("\\s*[*•▪]\\s*|\\s+[/\\-]\\s+|\\s+\\d{1,2}\\.\\s+");
    private static final Pattern NUMBERED = Pattern.compile("\\s+\\d{1,2}\\.\\s+");
    /**
     * 하는 일이 아니라 배우는 것·목표인 항목('~에 대한 이해', '~ 교육', '회사 소개', 'OJT', '~ 역량 향상')은 카드가 아니다.
     * '이해관계자'는 하는 일이라 남긴다.
     */
    private static final Pattern NOT_A_TASK = Pattern.compile(
            "이해(?!관계)|교육|학습|자세|향상|소개|OJT|오리엔테이션|인수인계|평가|피드백|숙지|높이고자|목표로|함양|쌓고|Point|부서\\s*실습");
    static final int CARD_MIN = 6;
    static final int CARD_MAX = 50;
    static final int CARDS_PER_JOB = 4;
    static final int CARDS_MAX = 60;
    static final double SAME_CARD = 0.8;

    private ExploreText() {
    }

    /** 학생 글 하나를 가리고 앞뒤·가운데 공백을 정리한다. */
    static String mask(String s) {
        if (s == null) {
            return null;
        }
        String t = EMAIL.matcher(s).replaceAll(MASK);
        t = PHONE.matcher(t).replaceAll(MASK);
        t = LONG_NUMBER.matcher(t).replaceAll(MASK);
        return t.strip().replaceAll("\\s+", " ");
    }

    /** 대조용: 무시하는 글자를 지운다. 구절이면 끝 문장부호도 뗀다. */
    static String squash(String s) {
        return s == null ? "" : NOISE.matcher(s).replaceAll("");
    }

    static String squashQuote(String q) {
        return squash(TRAILING_PUNCT.matcher(q == null ? "" : q.strip()).replaceFirst(""));
    }

    /** AI에 보내는 학생 글. 학과·학년·평점은 없다. */
    record Student(List<String> experiences, List<String> cards, String interestText) {

        String text() {
            StringBuilder b = new StringBuilder();
            if (experiences.isEmpty()) {
                b.append("해 본 일: 쓰지 않음\n");
            }
            for (int i = 0; i < experiences.size(); i++) {
                b.append("해 본 일 ").append(i + 1).append(": ").append(experiences.get(i)).append('\n');
            }
            b.append(cards.isEmpty() ? "하고 싶은 일: 고르지 않음" : "하고 싶은 일(고른 카드): " + String.join(" / ", cards))
                    .append('\n');
            b.append("관심 분야: ").append(interestText == null || interestText.isBlank() ? "적지 않음" : interestText);
            return b.toString();
        }

        /** 학생 구절을 찾을 조각(경험 · 카드 · 관심 분야 하나씩). */
        List<String> pieces() {
            List<String> out = new ArrayList<>(experiences);
            out.addAll(cards);
            if (interestText != null && !interestText.isBlank()) {
                out.add(interestText);
            }
            return out;
        }
    }

    /** 직무 원문의 칸. 쪽은 운영계획서 근거 쪽(field_evidence). */
    enum Field { OVERVIEW, COMPETENCY, GOAL, WEEKLY, TITLE, TEAM }

    record Piece(Field field, String text) {
    }

    /** 직무 구절을 찾을 조각. 앞의 칸이 먼저(쪽이 있는 칸 → 주차 계획 → 직무명·부서). */
    static List<Piece> pieces(JobDoc d) {
        List<Piece> out = new ArrayList<>();
        addLines(out, Field.OVERVIEW, d.overview());
        addLines(out, Field.COMPETENCY, d.competencies());
        addLines(out, Field.GOAL, d.educationGoal());
        for (String w : d.weeklyPlan()) {
            out.add(new Piece(Field.WEEKLY, w));
        }
        out.add(new Piece(Field.TITLE, d.title()));
        out.add(new Piece(Field.TEAM, d.team()));
        return out;
    }

    private static void addLines(List<Piece> out, Field field, String text) {
        for (String line : lines(text)) {
            out.add(new Piece(field, line));
            for (String part : INLINE_BULLET.split(line)) {
                if (!part.isBlank() && !part.strip().equals(line.strip())) {
                    out.add(new Piece(field, part));
                }
            }
        }
    }

    /** 탐색 순서 시스템 글의 직무 한 칸(E7 job_card와 같은 모양·길이). */
    static String rankCard(JobDoc d) {
        return "[" + d.jobId() + "] " + d.team() + " · " + d.title() + "\n"
                + "하는 일: " + cut(d.overview(), 260) + "\n"
                + "교육 목표: " + cut(d.educationGoal(), 120) + "\n"
                + "요구 역량: " + cut(d.competencies(), 120) + "\n"
                + "주차 계획: " + cut(String.join(" / ", d.weeklyPlan()), 260);
    }

    /** '왜 맞나요' 사용자 글: 학생 글 + 직무 원문 전부. 결과 밖 자리면 머리에 적는다. */
    static String whyMessage(Student student, JobDoc d, boolean outside) {
        StringBuilder b = new StringBuilder(student.text()).append("\n\n");
        b.append("# 자리 [").append(d.jobId()).append("] ").append(d.team()).append(" · ").append(d.title());
        if (outside) {
            b.append(" (탐색 5곳 밖)");
        }
        b.append('\n');
        b.append("하는 일: ").append(orDash(d.overview())).append('\n');
        b.append("교육 목표: ").append(orDash(d.educationGoal())).append('\n');
        b.append("요구 역량: ").append(orDash(d.competencies())).append('\n');
        b.append("주차 계획:");
        if (d.weeklyPlan().isEmpty()) {
            b.append(" —");
        }
        for (String w : d.weeklyPlan()) {
            b.append("\n- ").append(w);
        }
        return b.toString();
    }

    /**
     * 직무 하나의 '하고 싶은 일' 카드 글(최대 4개, 원문 그대로 6~50자). 직무 개요 항목에서 하는 일이 아닌 항목(이해·교육·
     * 소개·목표)을 빼고, 2개가 안 되면 주차 계획 항목을 더한다. 기관 이름은 '회사'로 가린다.
     */
    static List<String> cardTexts(JobDoc d) {
        Set<String> seen = new HashSet<>();
        List<String> out = new ArrayList<>();
        for (String line : lines(d.overview())) {
            for (String part : INLINE_BULLET.split(line)) {
                addCard(out, seen, d, part);
            }
        }
        if (out.size() < 2) {
            for (String w : d.weeklyPlan()) {
                for (String part : NUMBERED.split(w)) {
                    for (String piece : part.split("\\s+/\\s+")) {
                        for (String task : commaTasks(piece)) {
                            addCard(out, seen, d, task);
                        }
                    }
                }
            }
        }
        return out.size() > CARDS_PER_JOB ? List.copyOf(out.subList(0, CARDS_PER_JOB)) : out;
    }

    private static void addCard(List<String> out, Set<String> seen, JobDoc d, String raw) {
        String c = maskInstitution(EvidenceText.clean(raw), d.institution());
        if (c.length() < CARD_MIN || c.length() > CARD_MAX || NOT_A_TASK.matcher(c).find() || !seen.add(squash(c))) {
            return;
        }
        out.add(c);
    }

    /**
     * 주차 항목을 괄호 밖 쉼표로 나눈다 — 나눈 조각이 모두 6자 이상일 때만(일을 쉼표로 이은 것). 짧은 조각이 있으면
     * 이름을 늘어놓은 것('중국 패션 셀러, KOL, …')이라 나누지 않는다.
     */
    static List<String> commaTasks(String s) {
        String[] parts = s.split(",\\s*(?![^()]*\\))");
        for (String p : parts) {
            if (EvidenceText.clean(p).length() < CARD_MIN) {
                return List.of(s);
            }
        }
        return List.of(parts);
    }

    /** 기관 이름(앞의 (주)·주식회사 포함)을 '회사'로. */
    static String maskInstitution(String text, String institution) {
        if (institution == null || institution.isBlank()) {
            return text;
        }
        String name = institution.strip();
        return Pattern.compile("(?:\\(주\\)|㈜|주식회사)?\\s*" + Pattern.quote(name)).matcher(text).replaceAll("회사");
    }

    /**
     * 후보 직무의 카드를 직무를 번갈아 가며 놓는다(앞쪽이 한 회사로 몰리지 않게). 거의 같은 글(글자 2-gram 자카드 0.8 이상)은
     * 먼저 나온 것 하나만. 최대 60개.
     */
    static List<ExploreDtos.Card> cards(List<JobDoc> candidates) {
        List<List<String>> perJob = candidates.stream().map(ExploreText::cardTexts).toList();
        List<ExploreDtos.Card> out = new ArrayList<>();
        List<Set<String>> grams = new ArrayList<>();
        int depth = perJob.stream().mapToInt(List::size).max().orElse(0);
        for (int n = 0; n < depth && out.size() < CARDS_MAX; n++) {
            for (int j = 0; j < candidates.size() && out.size() < CARDS_MAX; j++) {
                List<String> texts = perJob.get(j);
                if (n >= texts.size()) {
                    continue;
                }
                Set<String> g = bigrams(texts.get(n));
                if (grams.stream().anyMatch(x -> jaccard(x, g) >= SAME_CARD)) {
                    continue;
                }
                grams.add(g);
                out.add(new ExploreDtos.Card(candidates.get(j).jobId() + "-" + (n + 1), texts.get(n)));
            }
        }
        return out;
    }

    static Set<String> bigrams(String s) {
        String t = squash(s);
        Set<String> out = new LinkedHashSet<>();
        for (int i = 0; i + 2 <= t.length(); i++) {
            out.add(t.substring(i, i + 2));
        }
        return out;
    }

    static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) {
            return 1;
        }
        Set<String> inter = new HashSet<>(a);
        inter.retainAll(b);
        return (double) inter.size() / (a.size() + b.size() - inter.size());
    }

    private static List<String> lines(String s) {
        if (s == null || s.isBlank()) {
            return List.of();
        }
        return List.of(s.split("\\R")).stream().filter(x -> !x.isBlank()).toList();
    }

    private static String cut(String s, int max) {
        if (s == null || s.isBlank()) {
            return "—";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static String orDash(String s) {
        return s == null || s.isBlank() ? "—" : s;
    }
}
