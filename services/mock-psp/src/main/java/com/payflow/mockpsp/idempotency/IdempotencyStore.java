package com.payflow.mockpsp.idempotency;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * mock-psp's side of idempotency: the same key with the same request always gets the same
 * answer, and nothing is processed twice. This is the in-memory counterpart of the
 * {@code INSERT ... ON CONFLICT} claim payment-service uses for its own inbound keys.
 *
 * <p>Every operation on one key is a single atomic map operation, so exactly one of several
 * simultaneous requests wins the claim.
 */
@Component
public class IdempotencyStore {

    /** Identifies "the same request": where it was sent and what it asked for. */
    public record Fingerprint(String path, Object request) {
    }

    /** A finished answer, stored so a retry can replay it. */
    public record Completed(Fingerprint fingerprint, int status, Object body) implements Entry {
    }

    /** What a caller learns when it tries to claim a key. */
    public sealed interface Claim {

        /** The key was free; the caller now owns it and must complete or release it. */
        record Claimed() implements Claim {
        }

        /** The same request already finished; replay this answer. */
        record Replay(Completed completed) implements Claim {
        }

        /** The key belongs to a different request. */
        record Reused() implements Claim {
        }

        /** The same request is still running. */
        record InUse() implements Claim {
        }
    }

    sealed interface Entry permits InProgress, Completed {
    }

    private record InProgress(Fingerprint fingerprint) implements Entry {
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    // Separate from entries on purpose: an attempt count must survive a release,
    // otherwise the "flaky" scenario could never reach its successful attempt.
    private final Map<String, AtomicInteger> attempts = new ConcurrentHashMap<>();

    public Claim claim(String key, Fingerprint fingerprint) {
        Entry existing = entries.putIfAbsent(key, new InProgress(fingerprint));
        if (existing == null) {
            return new Claim.Claimed();
        }
        Fingerprint owner = switch (existing) {
            case InProgress inProgress -> inProgress.fingerprint();
            case Completed completed -> completed.fingerprint();
        };
        if (!owner.equals(fingerprint)) {
            return new Claim.Reused();
        }
        return switch (existing) {
            case InProgress ignored -> new Claim.InUse();
            case Completed completed -> new Claim.Replay(completed);
        };
    }

    public void complete(String key, Fingerprint fingerprint, int status, Object body) {
        entries.put(key, new Completed(fingerprint, status, body));
    }

    /** Frees a claimed key so a retry runs from scratch. */
    public void release(String key) {
        entries.remove(key);
    }

    /** Counts one more attempt for this key and returns the new total, starting at 1. */
    public int recordAttempt(String key) {
        return attempts.computeIfAbsent(key, ignored -> new AtomicInteger()).incrementAndGet();
    }
}
