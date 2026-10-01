package kr.ac.skuniv.coopradar.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 로그인 없이 부를 수 있는 API. {@code /api/**}는 이 표시가 없으면 로그인이 필요하다(기본이 막힘, ADR-0013).
 * docs/api 목록의 권한 '공개'와 같아야 한다(ContractTest).
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface PublicApi {
}
