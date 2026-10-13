package io.andreygrigoriev.yfin.internal.cache;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.andreygrigoriev.yfin.spi.Cache;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link Cache} backed by one JSON object file per namespace ({@code <dir>/<namespace>.json}).
 *
 * <p>A namespace file is read lazily on first access and then served from memory. Every write
 * re-reads the file, applies its one change and rewrites the whole file through a temp file and an
 * atomic move, so readers never see a partial file and writers do not drop entries another {@code
 * FileCache} on the same directory wrote meanwhile. Writers in one JVM are serialized per file;
 * across processes this is best effort (no file lock), so a truly simultaneous write can still win
 * over another. The directory is created on first write. A missing, unreadable or corrupt file is
 * treated as empty (corruption is logged); I/O failures on write are logged and swallowed, as the
 * {@link Cache} contract requires.
 *
 * <p>Thread-safe: operations on the same namespace file are serialized (also across instances),
 * different namespaces do not block each other. Uses the Jackson tree model only, so no reflection metadata is needed.
 */
public final class FileCache implements Cache {

    private static final Logger LOG = LoggerFactory.getLogger(FileCache.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern NAMESPACE = Pattern.compile("[A-Za-z0-9_-]+");
    /** One monitor per namespace file, shared by every instance in this JVM. */
    private static final ConcurrentHashMap<Path, Object> FILE_LOCKS = new ConcurrentHashMap<>();

    private final Path dir;
    private final ConcurrentHashMap<String, Namespace> namespaces = new ConcurrentHashMap<>();

    public FileCache(Path dir) {
        this.dir = Objects.requireNonNull(dir, "dir");
    }

    /** Cache in the {@linkplain #defaultDir() default directory}. */
    public static FileCache atDefaultLocation() {
        return new FileCache(defaultDir());
    }

    /** {@code $XDG_CACHE_HOME/yfin} if set, else {@code ~/.cache/yfin}. */
    public static Path defaultDir() {
        return defaultDir(System.getenv("XDG_CACHE_HOME"), System.getProperty("user.home"));
    }

    static Path defaultDir(@Nullable String xdgCacheHome, String userHome) {
        if (xdgCacheHome != null && !xdgCacheHome.isBlank()) {
            return Path.of(xdgCacheHome, "yfin");
        }
        return Path.of(userHome, ".cache", "yfin");
    }

    public Path dir() {
        return dir;
    }

    @Override
    public Optional<String> get(String namespace, String key) {
        Objects.requireNonNull(key, "key");
        Namespace ns = namespace(namespace);
        synchronized (ns.lock) {
            return Optional.ofNullable(ns.entries().get(key));
        }
    }

    @Override
    public void put(String namespace, String key, String value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        Namespace ns = namespace(namespace);
        synchronized (ns.lock) {
            if (!value.equals(ns.entries().get(key))) {
                ns.update(key, value);
            }
        }
    }

    @Override
    public void invalidate(String namespace, String key) {
        Objects.requireNonNull(key, "key");
        Namespace ns = namespace(namespace);
        synchronized (ns.lock) {
            ns.update(key, null); // checks the file: another instance may have written the key
        }
    }

    private Namespace namespace(String name) {
        Objects.requireNonNull(name, "namespace");
        if (!NAMESPACE.matcher(name).matches()) {
            throw new IllegalArgumentException("namespace must match " + NAMESPACE + ": " + name);
        }
        return namespaces.computeIfAbsent(name, n -> new Namespace(dir.resolve(n + ".json")));
    }

    /** One namespace file and its in-memory copy. Guarded by {@link #lock}. */
    private static final class Namespace {
        private final Path file;
        final Object lock;
        private @Nullable Map<String, String> entries;

        Namespace(Path file) {
            this.file = file;
            this.lock = FILE_LOCKS.computeIfAbsent(file.toAbsolutePath().normalize(), p -> new Object());
        }

        Map<String, String> entries() {
            if (entries == null) {
                entries = load(file);
            }
            return entries;
        }

        /**
         * Sets {@code key} to {@code value} ({@code null} removes it) on top of the file's current
         * content, which another instance or process may have changed since it was loaded.
         */
        void update(String key, @Nullable String value) {
            Map<String, String> read = read(file);
            // unreadable or corrupt: keep what we have in memory rather than drop it
            Map<String, String> fresh = read != null ? read : new HashMap<>(entries());
            boolean changed = value == null ? fresh.remove(key) != null : !value.equals(fresh.put(key, value));
            entries = fresh;
            if (changed) {
                write(fresh);
            }
        }

        private void write(Map<String, String> snapshot) {
            ObjectNode node = JSON.createObjectNode();
            snapshot.forEach(node::put);
            Path dir = file.getParent();
            Path tmp = null;
            try {
                Files.createDirectories(dir);
                tmp = Files.createTempFile(dir, file.getFileName().toString(), ".tmp");
                Files.writeString(tmp, JSON.writeValueAsString(node), StandardCharsets.UTF_8);
                try {
                    Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                }
                tmp = null;
            } catch (IOException | RuntimeException e) {
                LOG.warn("cannot write cache file {}: {}", file, e.toString());
            } finally {
                if (tmp != null) {
                    try {
                        Files.deleteIfExists(tmp);
                    } catch (IOException ignored) {
                        // best effort
                    }
                }
            }
        }

        private static Map<String, String> load(Path file) {
            Map<String, String> result = read(file);
            return result != null ? result : new HashMap<>();
        }

        /** The file's entries: empty when it does not exist, {@code null} when unreadable or corrupt. */
        private static @Nullable Map<String, String> read(Path file) {
            Map<String, String> result = new HashMap<>();
            String content;
            try {
                content = Files.readString(file, StandardCharsets.UTF_8);
            } catch (NoSuchFileException e) {
                return result;
            } catch (IOException | RuntimeException e) {
                LOG.warn("cannot read cache file {}: {}", file, e.toString());
                return null;
            }
            try {
                JsonNode root = JSON.readTree(content);
                if (root == null || !root.isObject()) {
                    LOG.warn("corrupt cache file {} (not a JSON object), ignoring it", file);
                    return null;
                }
                for (Map.Entry<String, JsonNode> e : root.properties()) {
                    if (e.getValue().isTextual()) {
                        result.put(e.getKey(), e.getValue().asText());
                    }
                }
            } catch (IOException e) {
                LOG.warn("corrupt cache file {}, ignoring it: {}", file, e.toString());
                return null;
            }
            return result;
        }
    }
}
