package kr.ac.skuniv.coopradar.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import tools.jackson.databind.DatabindException;

/**
 * 오류를 계약 형식 {@code {code, message, fields?}}로 바꾼다(docs/api/README.md '오류').
 * 인증 인터셉터에서 던진 예외도 여기로 온다. 입력값·요청 본문은 응답과 로그에 넣지 않는다(ADR-0008).
 */
@RestControllerAdvice
public class ApiErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiErrorHandler.class);

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorBody(ErrorCode code, String message, List<FieldProblem> fields) {
    }

    public record FieldProblem(String field, String reason) {
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ErrorBody> api(ApiException e) {
        return body(e.code(), e.getMessage(), e.fields());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorBody> invalid(MethodArgumentNotValidException e) {
        List<FieldProblem> fields = new ArrayList<>();
        for (FieldError f : e.getBindingResult().getFieldErrors()) {
            fields.add(new FieldProblem(f.getField(), f.getDefaultMessage()));
        }
        e.getBindingResult().getGlobalErrors()
                .forEach(g -> fields.add(new FieldProblem(g.getObjectName(), g.getDefaultMessage())));
        return body(ErrorCode.INVALID_INPUT, "입력값을 확인해 주세요", fields);
    }

    /** 본문이 없거나 JSON이 깨졌거나 타입·코드값이 틀림. 거부된 값은 돌려주지 않는다. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorBody> unreadable(HttpMessageNotReadableException e) {
        List<FieldProblem> fields = null;
        if (e.getCause() instanceof DatabindException de && !de.getPath().isEmpty()) {
            StringBuilder path = new StringBuilder();
            for (var ref : de.getPath()) {
                if (ref.getPropertyName() != null) {
                    if (!path.isEmpty()) {
                        path.append('.');
                    }
                    path.append(ref.getPropertyName());
                } else if (ref.getIndex() >= 0) {
                    path.append('[').append(ref.getIndex()).append(']');
                }
            }
            fields = List.of(new FieldProblem(path.toString(), "형식이나 값이 맞지 않아요"));
        }
        return body(ErrorCode.INVALID_INPUT, "요청 본문을 읽을 수 없어요", fields);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ErrorBody> typeMismatch(MethodArgumentTypeMismatchException e) {
        return body(ErrorCode.INVALID_INPUT, "입력값을 확인해 주세요",
                List.of(new FieldProblem(e.getName(), "형식이 맞지 않아요")));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorBody> unexpected(Exception e) throws Exception {
        if (e instanceof org.springframework.web.ErrorResponse) {
            throw e; // 405·415·404 같은 MVC 기본 오류는 Spring 기본 처리에 맡긴다
        }
        log.error("처리하지 못한 오류", e);
        return body(ErrorCode.INTERNAL, "잠시 뒤 다시 시도해 주세요", null);
    }

    private static ResponseEntity<ErrorBody> body(ErrorCode code, String message, List<FieldProblem> fields) {
        return ResponseEntity.status(code.status()).body(new ErrorBody(code, message, fields));
    }
}
