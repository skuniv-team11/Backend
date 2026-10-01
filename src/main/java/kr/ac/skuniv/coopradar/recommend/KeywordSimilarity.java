package kr.ac.skuniv.coopradar.recommend;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

/**
 * 관심 문장 ↔ 직무 텍스트의 키워드 유사도: 글자 2~3-gram TF-IDF 코사인(ADR-0018).
 * E5의 키워드 기준선({@code pipeline/e5_embedding/hit_at_k.py}의 TfidfChar)과 같은 방식이다. 외부 호출이 없다.
 */
final class KeywordSimilarity {

    private final Map<String, Integer> documentFrequency = new HashMap<>();
    private final int documents;

    /** IDF는 직무 텍스트와 질의를 모두 문서로 보고 센다(E5와 같음). */
    KeywordSimilarity(Collection<String> corpus) {
        for (String text : corpus) {
            for (String gram : new HashSet<>(grams(text).keySet())) {
                documentFrequency.merge(gram, 1, Integer::sum);
            }
        }
        this.documents = corpus.size();
    }

    Map<String, Double> vector(String text) {
        Map<String, Double> v = new HashMap<>();
        grams(text).forEach((gram, count) -> v.put(gram,
                count * Math.log((documents + 1.0) / (documentFrequency.getOrDefault(gram, 0) + 1.0))));
        return v;
    }

    static double cosine(Map<String, Double> a, Map<String, Double> b) {
        double dot = 0;
        for (var e : a.entrySet()) {
            Double other = b.get(e.getKey());
            if (other != null) {
                dot += e.getValue() * other;
            }
        }
        double na = norm(a);
        double nb = norm(b);
        return na == 0 || nb == 0 ? 0 : dot / (na * nb);
    }

    /** 공백을 하나로 줄인 뒤 2·3글자 조각. */
    static Map<String, Integer> grams(String text) {
        String t = text == null ? "" : text.strip().replaceAll("\\s+", " ");
        Map<String, Integer> out = new HashMap<>();
        for (int k = 2; k <= 3; k++) {
            for (int i = 0; i + k <= t.length(); i++) {
                out.merge(t.substring(i, i + k), 1, Integer::sum);
            }
        }
        return out;
    }

    private static double norm(Map<String, Double> v) {
        double s = 0;
        for (double x : v.values()) {
            s += x * x;
        }
        return Math.sqrt(s);
    }
}
