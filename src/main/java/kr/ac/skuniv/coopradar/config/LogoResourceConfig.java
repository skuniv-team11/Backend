package kr.ac.skuniv.coopradar.config;

import java.time.Duration;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 기관 로고(/logos/{기관 id}.png, ADR-0019)와 소개서 사진(/photos/{기관 id}/{순번}.jpg, ADR-0030) 정적 파일.
 * 로그인 없이 받는다(/api 밖이라 인터셉터가 없다). 프론트는 &lt;img&gt;로 띄우므로 CORS가 필요 없다.
 * 같은 파일 이름으로 바꿔 넣을 수 있어 캐시는 하루만 둔다.
 */
@Configuration
public class LogoResourceConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/logos/**")
                .addResourceLocations("classpath:/static/logos/")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePublic());
        registry.addResourceHandler("/photos/**")
                .addResourceLocations("classpath:/static/photos/")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePublic());
    }
}
