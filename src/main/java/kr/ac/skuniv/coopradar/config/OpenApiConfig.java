package kr.ac.skuniv.coopradar.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger UI의 '구현' 스펙(/v3/api-docs) 머리말과 로그인 토큰 입력칸.
 * '계약' 스펙은 정적 파일(static/openapi/contract.json)이라 여기서 만들지 않는다(ADR-0011).
 */
@Configuration
public class OpenApiConfig {

    public static final String BEARER = "bearerAuth";

    @Bean
    OpenAPI implementedApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("현장뛰자 API — 구현")
                        .version("0.0.1")
                        .description("지금 코드에 있는 엔드포인트만 보인다(springdoc이 컨트롤러에서 만든다). "
                                + "24개 전체 계약은 위 드롭다운의 '계약'에서 본다."))
                .components(new Components().addSecuritySchemes(BEARER, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")));
        // 공개 API(/api/ping 등)가 있어 전체에 걸지 않는다. 로그인이 필요한 컨트롤러에
        // @SecurityRequirement(name = OpenApiConfig.BEARER)를 붙인다.
    }
}
