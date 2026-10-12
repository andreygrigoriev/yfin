package io.andreygrigoriev.yfin.internal.cache;

import io.andreygrigoriev.yfin.spi.Cache;
import java.util.Optional;

/** {@link Cache} that stores nothing. Exposed through {@link Cache#none()}. */
public enum NoCache implements Cache {
    INSTANCE;

    @Override
    public Optional<String> get(String namespace, String key) {
        return Optional.empty();
    }

    @Override
    public void put(String namespace, String key, String value) {}

    @Override
    public void invalidate(String namespace, String key) {}
}
