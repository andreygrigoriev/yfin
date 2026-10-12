package io.andreygrigoriev.yfin.spi;

import io.andreygrigoriev.yfin.internal.cache.NoCache;
import java.util.Optional;

/**
 * String key-value store for data worth keeping across client instances (timezones, cookies,
 * ISIN lookups). Entries are grouped by namespace. Implementations must be thread-safe and must
 * not throw on I/O problems: a failing cache behaves like a miss.
 */
public interface Cache {

    /** Value stored under {@code namespace}/{@code key}, if any. */
    Optional<String> get(String namespace, String key);

    /** Stores {@code value}, replacing any previous value. */
    void put(String namespace, String key, String value);

    /** Removes the entry, if present. */
    void invalidate(String namespace, String key);

    /** A cache that stores nothing: every lookup misses. */
    static Cache none() {
        return NoCache.INSTANCE;
    }
}
