package kr.ac.skuniv.coopradar.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 이 역할만 부를 수 있다. 아니면 403 FORBIDDEN_ROLE. 메서드에 붙인 것이 클래스보다 먼저다.
 * docs/api 목록의 권한 'STUDENT'·'CENTER'와 같아야 한다(ContractTest).
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireRole {
    Role value();
}
