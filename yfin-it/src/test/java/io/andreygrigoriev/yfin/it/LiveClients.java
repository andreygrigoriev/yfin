package io.andreygrigoriev.yfin.it;

import io.andreygrigoriev.yfin.YFinance;
import io.andreygrigoriev.yfin.spi.Cache;

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
