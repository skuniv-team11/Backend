package kr.ac.skuniv.coopradar.common;

import java.time.Duration;

/** 429 RATE_LIMITED. 응답 헤더 Retry-After(초)에 다시 될 때까지 남은 시간을 넣는다. */
public class RateLimitedException extends ApiException {

    private final Duration retryAfter;

    public RateLimitedException(Duration retryAfter) {
        super(ErrorCode.RATE_LIMITED, "잠시 뒤 다시 시도해 주세요");
        this.retryAfter = retryAfter;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
