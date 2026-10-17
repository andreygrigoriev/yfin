package io.github.andreygrigoriev.yfin.it;

import io.github.andreygrigoriev.yfin.YFinance;
import io.github.andreygrigoriev.yfin.spi.Cache;

/** Client factory shared by the live integration tests. */
final class LiveClients {

    private LiveClients() {}

    /**
     * Client with the default JDK transport and no persistent cache, so every run exercises the
     * full cookie/crumb bootstrap against Yahoo.
     */
    static YFinance create() {
        return YFinance.builder().cache(Cache.none()).build();
    }
}
