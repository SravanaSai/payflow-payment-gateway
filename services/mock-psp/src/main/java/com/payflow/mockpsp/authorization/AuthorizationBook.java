package com.payflow.mockpsp.authorization;

import com.payflow.mockpsp.web.PspException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Every hold mock-psp has placed. In memory only: restarting the app forgets them. */
@Component
public class AuthorizationBook {

    private final Map<String, Authorization> holds = new ConcurrentHashMap<>();

    public Authorization create(long amountMinor, String currency, String merchantReference) {
        Authorization hold = new Authorization(
                "auth_" + UUID.randomUUID(), amountMinor, currency, merchantReference,
                Authorization.Status.APPROVED, 0, Instant.now());
        holds.put(hold.id(), hold);
        return hold;
    }

    public Optional<Authorization> find(String id) {
        return Optional.ofNullable(holds.get(id));
    }

    /** Read-only check, so a bad capture is rejected before any scenario runs. */
    public void checkCapturable(String id, long amountMinor) {
        Authorization hold = holds.get(id);
        if (hold == null) {
            throw unknown(id);
        }
        requireCapturable(hold, amountMinor);
    }

    /**
     * Captures a hold exactly once. The check and the update happen inside one atomic
     * {@code compute}, so two simultaneous captures cannot both succeed.
     */
    public Authorization capture(String id, long amountMinor) {
        Authorization captured = holds.computeIfPresent(id, (key, hold) -> {
            requireCapturable(hold, amountMinor);
            return hold.captured(amountMinor);
        });
        if (captured == null) {
            throw unknown(id);
        }
        return captured;
    }

    private static void requireCapturable(Authorization hold, long amountMinor) {
        if (hold.status() != Authorization.Status.APPROVED) {
            throw PspException.invalid("authorization_already_captured",
                    "Authorization " + hold.id() + " has already been captured.");
        }
        if (amountMinor > hold.amountMinor()) {
            throw PspException.invalid("amount_too_large",
                    "Capture amount " + amountMinor + " exceeds the authorized amount " + hold.amountMinor() + ".");
        }
    }

    private static PspException unknown(String id) {
        return PspException.notFound("No such authorization: " + id);
    }
}
