package com.paypalseed.creditrecord.infrastructure.web;

import com.paypalseed.creditrecord.api.dto.CreditRecordDtos.VerifiedRecord;
import com.paypalseed.creditrecord.application.usecases.ShareService;
import com.paypalseed.creditrecord.domain.model.RecordNotFoundException;
import java.net.URI;
import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public verification for partners: no sign-in. Read-only, one indexed lookup, and cacheable for a few seconds so a
 * partner's retries and a rate limiter in front of it are cheap. It takes no Actor on purpose.
 */
@RestController
class RecordVerificationController {

    static final Duration CACHE = Duration.ofSeconds(5);
    private static final String PROBLEM_TYPE_BASE = "https://problems.paypal-seed.dev/";
    /** A fixed instance: the default would echo the request path, token included, into the body and the logs. */
    private static final URI INSTANCE = URI.create("/api/v1/records/verify");

    private final ShareService shares;

    RecordVerificationController(ShareService shares) {
        this.shares = shares;
    }

    @GetMapping("/api/v1/records/verify/{token}")
    ResponseEntity<VerifiedRecord> verify(@PathVariable String token) {
        return ResponseEntity.ok().cacheControl(cache()).body(shares.verify(token));
    }

    /** Unknown, expired, revoked and tampered tokens get this one body, byte for byte. */
    @ExceptionHandler(RecordNotFoundException.class)
    ResponseEntity<ProblemDetail> notFound(RecordNotFoundException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        problem.setType(URI.create(PROBLEM_TYPE_BASE + e.errorCode().code()));
        problem.setTitle(e.errorCode().title());
        problem.setProperty("code", e.errorCode().code());
        problem.setInstance(INSTANCE);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).cacheControl(cache()).body(problem);
    }

    private static CacheControl cache() {
        return CacheControl.maxAge(CACHE).cachePublic();
    }
}
