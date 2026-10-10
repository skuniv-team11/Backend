package kr.ac.skuniv.coopradar.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 프론트(Cloudflare Pages)에서 오는 요청만 허용한다.
 * 허용 목록은 환경변수 CORS_ORIGINS(쉼표 구분, 패턴 가능)로 넣는다.
 * 예: https://fieldrun.pages.dev,https://*.fieldrun.pages.dev (운영 + 미리보기)
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    private final String[] allowedOriginPatterns;

    public CorsConfig(@Value("${app.cors.allowed-origin-patterns}") String[] allowedOriginPatterns) {
        this.allowedOriginPatterns = allowedOriginPatterns;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns(allowedOriginPatterns)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                // 다른 출처의 JS가 읽게: 429의 기다릴 초, CSV 파일 이름
                .exposedHeaders(HttpHeaders.RETRY_AFTER, HttpHeaders.CONTENT_DISPOSITION)
                .maxAge(3600);
    }
}
