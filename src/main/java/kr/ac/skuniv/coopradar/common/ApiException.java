package kr.ac.skuniv.coopradar.common;

import java.util.List;
import kr.ac.skuniv.coopradar.common.ApiErrorHandler.FieldProblem;

/**
 * 계약에 있는 오류 코드로 응답할 때 던진다. message는 화면에 보일 수 있는 문장이다(입력값을 넣지 않는다).
 * fields는 INVALID_INPUT일 때만 쓴다.
 */
public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final List<FieldProblem> fields;

    public ApiException(ErrorCode code, String message) {
        this(code, message, null);
    }

    public ApiException(ErrorCode code, String message, List<FieldProblem> fields) {
        super(message);
        this.code = code;
        this.fields = fields;
    }

    public static ApiException invalid(String field, String reason) {
        return new ApiException(ErrorCode.INVALID_INPUT, "입력값을 확인해 주세요", List.of(new FieldProblem(field, reason)));
    }

    public ErrorCode code() {
        return code;
    }

    public List<FieldProblem> fields() {
        return fields;
    }
}
