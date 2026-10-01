package kr.ac.skuniv.coopradar.auth;

import java.util.List;
import kr.ac.skuniv.coopradar.common.ApiException;
import kr.ac.skuniv.coopradar.common.ErrorCode;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 인증 인터셉터를 /api/**에 걸고, 컨트롤러가 {@code AuthUser} 인자를 받게 한다. */
@Configuration
public class AuthWebConfig implements WebMvcConfigurer {

    private final AuthInterceptor interceptor;

    public AuthWebConfig(AuthInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor).addPathPatterns("/api/**");
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new HandlerMethodArgumentResolver() {
            @Override
            public boolean supportsParameter(MethodParameter parameter) {
                return parameter.getParameterType() == AuthUser.class;
            }

            @Override
            public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mav,
                                          NativeWebRequest request, WebDataBinderFactory binderFactory) {
                Object user = request.getAttribute(AuthUser.ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
                if (user == null) {
                    // @PublicApi 메서드가 AuthUser를 받으려 한 경우. 설계 실수라 막는다
                    throw new ApiException(ErrorCode.AUTH_REQUIRED, "로그인이 필요해요");
                }
                return user;
            }
        });
    }
}
