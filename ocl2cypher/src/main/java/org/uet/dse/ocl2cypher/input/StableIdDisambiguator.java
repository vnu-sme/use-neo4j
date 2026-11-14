package org.uet.dse.ocl2cypher.input;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Deterministic file-order disambiguator for raw stable identities that may collide.
 * First occurrence keeps the raw name; the n-th repetition becomes {@code raw__n}
 * with suffix collision safety (skips any already-taken literal such as a
 * pre-existing {@code raw__1}).
 */
public final class StableIdDisambiguator {

    /** Reserved separator for generated occurrence suffixes: {@code raw__1}. */
    public static final String GENERATED_SUFFIX_SEPARATOR = "__";

    private final Map<String, Integer> nextSuffix = new LinkedHashMap<>();
    private final Set<String> usedIds = new LinkedHashSet<>();

    /** Returns the normalized stableId for {@code raw} and records it. */
    public String disambiguate(String raw) {
        Objects.requireNonNull(raw, "raw stable identity");
        if (raw.isBlank()) {
            throw new IllegalArgumentException("raw stable identity must not be blank");
        }
        if (!usedIds.contains(raw)) {
            usedIds.add(raw);
            nextSuffix.put(raw, 1);
            return raw;
        }
        int k = nextSuffix.getOrDefault(raw, 1);
        while (true) {
            String candidate = raw + GENERATED_SUFFIX_SEPARATOR + k;
            k++;
            if (!usedIds.contains(candidate)) {
                usedIds.add(candidate);
                nextSuffix.put(raw, k);
                return candidate;
            }
        }
    }

    /**
     * Records an identity created by an external/runtime operation. Repeated
     * reservation is idempotent and does not consume a generated suffix.
     */
    public void reserve(String id) {
        Objects.requireNonNull(id, "stable identity");
        if (id.isBlank()) {
            throw new IllegalArgumentException("stable identity must not be blank");
        }
        if (usedIds.add(id)) {
            nextSuffix.putIfAbsent(id, 1);
        }
    }
}
