package com.payflow.mockpsp.web;

import com.payflow.mockpsp.authorization.AuthorizationBook;
import com.payflow.mockpsp.config.MockPspProperties;
import com.payflow.mockpsp.idempotency.IdempotencyStore;
import com.payflow.mockpsp.idempotency.IdempotencyStore.Claim;
import com.payflow.mockpsp.idempotency.IdempotencyStore.Completed;
import com.payflow.mockpsp.idempotency.IdempotencyStore.Fingerprint;
import com.payflow.mockpsp.scenario.Scenario;
import com.payflow.mockpsp.scenario.ScenarioResolver;
import com.payflow.mockpsp.web.dto.AuthorizationResponse;
import com.payflow.mockpsp.web.dto.AuthorizeRequest;
import com.payflow.mockpsp.web.dto.CaptureRequest;
import com.payflow.mockpsp.web.dto.CaptureResponse;
import com.payflow.mockpsp.web.dto.PspErrorResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * The processor's three endpoints. Each POST follows the same path: claim the idempotency key,
 * let the amount choose a {@link Scenario}, apply it, and remember the answer under the key.
 */
@RestController
@RequestMapping("/v1/authorizations")
public class AuthorizationController {

    static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private final AuthorizationBook book;
    private final IdempotencyStore store;
    private final MockPspProperties properties;

    public AuthorizationController(AuthorizationBook book, IdempotencyStore store, MockPspProperties properties) {
        this.book = book;
        this.store = store;
        this.properties = properties;
    }

    @PostMapping
    public ResponseEntity<Object> authorize(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody AuthorizeRequest request) {
        return execute(
                idempotencyKey,
                new Fingerprint("/v1/authorizations", request),
                request.amountMinor(),
                () -> { },
                () -> AuthorizationResponse.from(
                        book.create(request.amountMinor(), request.currency(), request.merchantReference())));
    }

    @PostMapping("/{id}/captures")
    public ResponseEntity<Object> capture(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @PathVariable("id") String id,
            @Valid @RequestBody CaptureRequest request) {
        return execute(
                idempotencyKey,
                new Fingerprint("/v1/authorizations/" + id + "/captures", request),
                request.amountMinor(),
                // A bad capture is rejected before any scenario runs, so a 404 or 400 always wins.
                () -> book.checkCapturable(id, request.amountMinor()),
                () -> {
                    book.capture(id, request.amountMinor());
                    return CaptureResponse.succeeded(id, request.amountMinor());
                });
    }

    @GetMapping("/{id}")
    public AuthorizationResponse get(@PathVariable("id") String id) {
        return book.find(id)
                .map(AuthorizationResponse::from)
                .orElseThrow(() -> PspException.notFound("No such authorization: " + id));
    }

    /** What to send back, and how long to wait before sending it. */
    private record Result(ResponseEntity<Object> response, Duration delay) {
    }

    private ResponseEntity<Object> execute(
            String key,
            Fingerprint fingerprint,
            long amountMinor,
            Runnable precheck,
            Supplier<Object> apply) {
        if (key.isBlank()) {
            throw PspException.invalid("parameter_invalid", "The Idempotency-Key header must not be blank.");
        }
        switch (store.claim(key, fingerprint)) {
            case Claim.Replay replay -> {
                return replayed(replay.completed());
            }
            case Claim.Reused ignored -> throw PspException.idempotencyKeyReused();
            case Claim.InUse ignored -> throw PspException.idempotencyKeyInUse();
            case Claim.Claimed ignored -> {
                // The key is ours: fall through to do the work.
            }
        }

        Result result;
        try {
            precheck.run();
            result = decide(key, fingerprint, amountMinor, apply);
        } catch (RuntimeException ex) {
            // Nothing was recorded, so free the key and let a retry run from scratch.
            store.release(key);
            throw ex;
        }
        // The wait happens outside the try block: the answer is already stored,
        // and a failed wait must not erase it.
        pause(result.delay());
        return result.response();
    }

    private Result decide(String key, Fingerprint fingerprint, long amountMinor, Supplier<Object> apply) {
        Scenario scenario = ScenarioResolver.resolve(amountMinor);

        if (scenario == Scenario.DOWN) {
            throw PspException.unavailable();
        }
        if (scenario == Scenario.FLAKY && store.recordAttempt(key) <= properties.flakyFailures()) {
            throw PspException.unavailable();
        }
        if (scenario.isDecline()) {
            PspErrorResponse declined = PspErrorResponse.declined(scenario.declineCode(), scenario.declineMessage());
            store.complete(key, fingerprint, HttpStatus.PAYMENT_REQUIRED.value(), declined);
            return new Result(ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED).body(declined), Duration.ZERO);
        }

        Object body = apply.get();
        // Stored before any wait: even if the caller gives up on a slow reply, a retry
        // with the same key finds this answer instead of creating a second hold.
        store.complete(key, fingerprint, HttpStatus.CREATED.value(), body);
        Duration delay = scenario == Scenario.SLOW ? properties.slowDelay() : Duration.ZERO;
        return new Result(ResponseEntity.status(HttpStatus.CREATED).body(body), delay);
    }

    private ResponseEntity<Object> replayed(Completed completed) {
        return ResponseEntity.status(completed.status())
                .header(REPLAYED_HEADER, "true")
                .body(completed.body());
    }

    private static void pause(Duration delay) {
        if (delay.isZero()) {
            return;
        }
        try {
            Thread.sleep(delay);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw PspException.unavailable();
        }
    }
}
