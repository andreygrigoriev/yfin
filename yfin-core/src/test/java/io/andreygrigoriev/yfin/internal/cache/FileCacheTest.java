package io.andreygrigoriev.yfin.internal.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.andreygrigoriev.yfin.spi.Cache;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileCacheTest {

    @TempDir
    Path tmp;

    @Test
    void putGetInvalidate() {
        var cache = new FileCache(tmp);

        assertThat(cache.get("tz", "AAPL")).isEmpty();
        cache.put("tz", "AAPL", "America/New_York");
        assertThat(cache.get("tz", "AAPL")).contains("America/New_York");

        cache.put("tz", "AAPL", "Europe/London");
        assertThat(cache.get("tz", "AAPL")).contains("Europe/London");

        cache.invalidate("tz", "AAPL");
        assertThat(cache.get("tz", "AAPL")).isEmpty();
        cache.invalidate("tz", "missing");
    }

    @Test
    void namespacesAreIndependentFiles() {
        var cache = new FileCache(tmp);
        cache.put("tz", "k", "zone");
        cache.put("cookie", "k", "crumb");

        assertThat(cache.get("tz", "k")).contains("zone");
        assertThat(cache.get("cookie", "k")).contains("crumb");
        assertThat(tmp.resolve("tz.json")).exists();
        assertThat(tmp.resolve("cookie.json")).exists();
    }

    @Test
    void persistsAcrossInstances() {
        var first = new FileCache(tmp);
        first.put("tz", "AAPL", "America/New_York");
        first.put("tz", "MSFT", "America/New_York");
        first.invalidate("tz", "MSFT");

        var second = new FileCache(tmp);
        assertThat(second.get("tz", "AAPL")).contains("America/New_York");
        assertThat(second.get("tz", "MSFT")).isEmpty();
    }

    @Test
    void createsDirectoryOnFirstWriteOnly() {
        Path dir = tmp.resolve("nested/yfin");
        var cache = new FileCache(dir);

        assertThat(cache.get("tz", "AAPL")).isEmpty();
        assertThat(dir).doesNotExist();

        cache.put("tz", "AAPL", "America/New_York");
        assertThat(dir.resolve("tz.json")).isRegularFile();
    }

    @Test
    void corruptFileStartsEmptyAndIsRepairedOnWrite() throws Exception {
        Files.writeString(tmp.resolve("tz.json"), "{not json");
        var cache = new FileCache(tmp);

        assertThat(cache.get("tz", "AAPL")).isEmpty();
        cache.put("tz", "AAPL", "America/New_York");

        assertThat(new FileCache(tmp).get("tz", "AAPL")).contains("America/New_York");
    }

    @Test
    void nonObjectJsonAndNonTextValuesAreIgnored() throws Exception {
        Files.writeString(tmp.resolve("tz.json"), "[1,2]");
        Files.writeString(tmp.resolve("isin.json"), "{\"a\":\"x\",\"b\":1,\"c\":null}");
        var cache = new FileCache(tmp);

        assertThat(cache.get("tz", "0")).isEmpty();
        assertThat(cache.get("isin", "a")).contains("x");
        assertThat(cache.get("isin", "b")).isEmpty();
        assertThat(cache.get("isin", "c")).isEmpty();
    }

    @Test
    void unwritableDirectoryDoesNotThrow() throws Exception {
        Path file = tmp.resolve("not-a-dir");
        Files.writeString(file, "x");
        var cache = new FileCache(file);

        cache.put("tz", "AAPL", "America/New_York");
        assertThat(cache.get("tz", "AAPL")).contains("America/New_York");
    }

    @Test
    void rejectsUnsafeNamespace() {
        var cache = new FileCache(tmp);
        assertThatThrownBy(() -> cache.put("../evil", "k", "v")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cache.get("", "k")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void twoInstancesOnOneDirectoryKeepEachOthersEntries() throws Exception {
        var first = new FileCache(tmp);
        var second = new FileCache(tmp);
        first.get("tz", "warm-up"); // both load the (empty) file before anyone writes
        second.get("tz", "warm-up");
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<Future<?>>();
            for (var cache : List.of(first, second)) {
                String prefix = cache == first ? "a" : "b";
                futures.add(executor.submit(() -> {
                    start.await();
                    for (int i = 0; i < 50; i++) {
                        cache.put("tz", prefix + i, "v" + i);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (var f : futures) {
                f.get();
            }
        }
        second.invalidate("tz", "a0");

        var reopened = new FileCache(tmp);
        for (int i = 0; i < 50; i++) {
            assertThat(reopened.get("tz", "b" + i)).contains("v" + i);
            if (i > 0) {
                assertThat(reopened.get("tz", "a" + i)).contains("v" + i);
            }
        }
        assertThat(reopened.get("tz", "a0")).isEmpty();
    }

    @Test
    void concurrentPutsDoNotCorruptFile() throws Exception {
        var cache = new FileCache(tmp);
        int threads = 50;
        int perThread = 20;
        var start = new CountDownLatch(1);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<Future<?>>();
            for (int t = 0; t < threads; t++) {
                int id = t;
                futures.add(executor.submit(() -> {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        cache.put("tz", id + "-" + i, "v" + id + "-" + i);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (var f : futures) {
                f.get();
            }
        }

        var root = new ObjectMapper().readTree(Files.readString(tmp.resolve("tz.json")));
        assertThat(root.size()).isEqualTo(threads * perThread);
        try (Stream<Path> files = Files.list(tmp)) {
            assertThat(files).containsExactly(tmp.resolve("tz.json"));
        }
        var reloaded = new FileCache(tmp);
        for (int t = 0; t < threads; t++) {
            for (int i = 0; i < perThread; i++) {
                assertThat(reloaded.get("tz", t + "-" + i)).contains("v" + t + "-" + i);
            }
        }
    }

    @Test
    void defaultDirPrefersXdgCacheHome() {
        assertThat(FileCache.defaultDir("/xdg", "/home/u")).isEqualTo(Path.of("/xdg", "yfin"));
        assertThat(FileCache.defaultDir(null, "/home/u")).isEqualTo(Path.of("/home/u", ".cache", "yfin"));
        assertThat(FileCache.defaultDir(" ", "/home/u")).isEqualTo(Path.of("/home/u", ".cache", "yfin"));
    }

    @Test
    void noneStoresNothing() {
        Cache none = Cache.none();
        none.put("tz", "k", "v");
        assertThat(none.get("tz", "k")).isEmpty();
    }
}
