package kr.ac.skuniv.coopradar.common;

import org.springframework.http.HttpStatus;

/**
 * API 오류 코드. docs/api/README.md '오류 코드' 표와 같은 집합·상태 코드다(ContractTest가 대조).
 */
public enum ErrorCode {
    INVALID_INPUT(HttpStatus.BAD_REQUEST),
    CONSENT_REQUIRED(HttpStatus.BAD_REQUEST),
    RANK_INVALID(HttpStatus.BAD_REQUEST),
    AS_OF_OUT_OF_RANGE(HttpStatus.BAD_REQUEST),
    AUTH_REQUIRED(HttpStatus.UNAUTHORIZED),
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED),
    LOGIN_FAILED(HttpStatus.UNAUTHORIZED),
    FORBIDDEN_ROLE(HttpStatus.FORBIDDEN),
    PROFILE_NOT_FOUND(HttpStatus.NOT_FOUND),
    JOB_NOT_FOUND(HttpStatus.NOT_FOUND),
    PLAN_ITEM_NOT_FOUND(HttpStatus.NOT_FOUND),
    EMAIL_TAKEN(HttpStatus.CONFLICT),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS),
    INTERNAL(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
