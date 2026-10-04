package kr.ac.skuniv.coopradar.job;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 기관 로고 경로(ADR-0019). 파일은 {@code src/main/resources/static/logos/{기관 id}.png}이고
 * API 서버의 정적 경로 {@code /logos/{id}.png}로 나간다. 파일이 없는 기관은 null — 화면은 기관명 첫 글자로 대신한다.
 * 실행 중 외부 사이트에서 받지 않는다(ADR-0002).
 */
public final class InstitutionLogos {

    private static final Map<Integer, String> CACHE = new ConcurrentHashMap<>();
    private static final String NONE = "";

    private InstitutionLogos() {
    }

    /** 응답의 {@code logoPath}. 로고 파일이 없으면 null. */
    public static String pathFor(int institutionId) {
        String path = CACHE.computeIfAbsent(institutionId, id ->
                InstitutionLogos.class.getClassLoader().getResource("static/logos/" + id + ".png") != null
                        ? "/logos/" + id + ".png"
                        : NONE);
        return path.isEmpty() ? null : path;
    }
}
