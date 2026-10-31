package com.paypalseed.app;

import com.paypalseed.platform.kernel.DomainException;
import com.paypalseed.platform.kernel.ErrorCode;
import com.paypalseed.platform.kernel.Ids;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Every error leaves as RFC 9457 {@code application/problem+json} with a stable {@code type}.
 * Domain refusals carry their code and safe details. Anything unexpected is a 500 with a
 * correlation id and no internals.
 */
@RestControllerAdvice
class ApiErrorHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiErrorHandler.class);
    static final String TYPE_BASE = "https://problems.paypal-seed.dev/";

    @ExceptionHandler(DomainException.class)
    ResponseEntity<ProblemDetail> domain(DomainException e) {
        ErrorCode code = e.errorCode();
        ProblemDetail p = problem(status(code.kind()), code.code(), code.title(), e.getMessage());
        if (!e.details().isEmpty()) {
            p.setProperty("details", e.details());
        }
        return ResponseEntity.status(p.getStatus()).body(p);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ProblemDetail> integrity(DataIntegrityViolationException e) {
        UUID ref = Ids.newId();
        log.warn("Data integrity violation [{}]", ref, e);
        ProblemDetail p = problem(HttpStatus.CONFLICT, "conflict", "The request conflicts with the current state",
                "Reference " + ref);
        return ResponseEntity.status(p.getStatus()).body(p);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ProblemDetail> badArgument(IllegalArgumentException e) {
        ProblemDetail p = problem(HttpStatus.BAD_REQUEST, "invalid-request", "The request is not valid", e.getMessage());
        return ResponseEntity.status(p.getStatus()).body(p);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception e) {
        UUID ref = Ids.newId();
        log.error("Unhandled error [{}]", ref, e);
        ProblemDetail p = problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "Something went wrong",
                "Reference " + ref);
        return ResponseEntity.status(p.getStatus()).body(p);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException e, HttpHeaders headers,
                                                                  HttpStatusCode status, WebRequest request) {
        Map<String, String> fields = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors().forEach(f -> fields.put(f.getField(), f.getDefaultMessage()));
        ProblemDetail p = problem(HttpStatus.BAD_REQUEST, "validation-failed", "Some fields are not valid", "See fields");
        p.setProperty("fields", fields);
        return ResponseEntity.badRequest().body(p);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException e,
                                                                            HttpHeaders headers, HttpStatusCode status,
                                                                            WebRequest request) {
        ProblemDetail p = problem(HttpStatus.BAD_REQUEST, "validation-failed", "Some parameters are not valid",
                e.getMessage());
        return ResponseEntity.badRequest().body(p);
    }

    /** Spring MVC's own errors (missing header, unreadable body, 404 route...) get a stable type too. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception e, Object body, HttpHeaders headers,
                                                             HttpStatusCode status, WebRequest request) {
        if (body instanceof ProblemDetail p && p.getType().toString().equals("about:blank")) {
            p.setType(URI.create(TYPE_BASE + (status.is4xxClientError() ? "invalid-request" : "internal-error")));
            p.setProperty("code", status.is4xxClientError() ? "invalid-request" : "internal-error");
        }
        return super.handleExceptionInternal(e, body, headers, status, request);
    }

    private static ProblemDetail problem(HttpStatus status, String code, String title, String detail) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detail);
        p.setType(URI.create(TYPE_BASE + code));
        p.setTitle(title);
        p.setProperty("code", code);
        return p;
    }

    private static HttpStatus status(ErrorCode.Kind kind) {
        return switch (kind) {
            case VALIDATION -> HttpStatus.UNPROCESSABLE_ENTITY;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case PRECONDITION_FAILED -> HttpStatus.PRECONDITION_FAILED;
            case UNAUTHENTICATED -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
        };
    }
}
