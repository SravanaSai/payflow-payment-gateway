package com.payflow.common.security;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The set of peppers this service can use, with one designated current.
 *
 * <p>Rotation works because hashes record the pepper version that produced them.
 * New hashes always use {@link #current()}; older hashes remain verifiable
 * against a retained previous pepper and are re-hashed on their next successful
 * authentication. Once no rows reference an old version it can be dropped.
 *
 * <p>Without this, rotating the pepper would invalidate every stored hash at
 * once — the original secrets are gone, so hashes cannot be recomputed.
 */
public final class PepperRegistry {

    private final int currentVersion;
    private final Map<Integer, Pepper> byVersion;

    private PepperRegistry(int currentVersion, Map<Integer, Pepper> byVersion) {
        this.currentVersion = currentVersion;
        this.byVersion = byVersion;
    }

    /** Registry with a single pepper — the normal case outside a rotation window. */
    public static PepperRegistry single(Pepper pepper) {
        Objects.requireNonNull(pepper, "pepper must not be null");
        return of(java.util.List.of(pepper), pepper.version());
    }

    /**
     * @param peppers        the current pepper plus any previous ones still in use
     * @param currentVersion version used for all newly created hashes
     */
    public static PepperRegistry of(Collection<Pepper> peppers, int currentVersion) {
        Objects.requireNonNull(peppers, "peppers must not be null");
        if (peppers.isEmpty()) {
            throw new IllegalArgumentException("at least one pepper is required");
        }

        Map<Integer, Pepper> byVersion = new LinkedHashMap<>();
        for (Pepper pepper : peppers) {
            Pepper existing = byVersion.put(pepper.version(), pepper);
            if (existing != null) {
                throw new IllegalArgumentException("duplicate pepper version: " + pepper.version());
            }
        }
        if (!byVersion.containsKey(currentVersion)) {
            throw new IllegalArgumentException(
                    "no pepper supplied for current version " + currentVersion);
        }
        return new PepperRegistry(currentVersion, Map.copyOf(byVersion));
    }

    public int currentVersion() {
        return currentVersion;
    }

    public Pepper current() {
        return byVersion.get(currentVersion);
    }

    /** Every pepper this process can hash with — used to build lookup candidates on rotation. */
    public Collection<Pepper> all() {
        return byVersion.values();
    }

    /**
     * Empty when a hash references a retired pepper. Callers must treat that as
     * a failed authentication, never as a server error.
     */
    public Optional<Pepper> forVersion(int version) {
        return Optional.ofNullable(byVersion.get(version));
    }

    @Override
    public String toString() {
        return "PepperRegistry[currentVersion=" + currentVersion
                + ", versions=" + byVersion.keySet() + "]";
    }
}
