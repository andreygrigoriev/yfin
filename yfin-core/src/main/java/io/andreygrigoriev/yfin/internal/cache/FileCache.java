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
 * rewrites the whole namespace file through a temp file and an atomic move, so readers never see a
 * partial file. The directory is created on first write. A missing, unreadable or corrupt file is
 * treated as empty (corruption is logged); I/O failures on write are logged and swallowed, as the
 * {@link Cache} contract requires.
 *
 * <p>Thread-safe: operations on the same namespace are serialized, different namespaces do not
 * block each other. Uses the Jackson tree model only, so no reflection metadata is needed.
 */
public final class FileCache implements Cache {

    private static final Logger LOG = LoggerFactory.getLogger(FileCache.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern NAMESPACE = Pattern.compile("[A-Za-z0-9_-]+");

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
        synchronized (ns) {
            return Optional.ofNullable(ns.entries().get(key));
        }
    }

    @Override
    public void put(String namespace, String key, String value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        Namespace ns = namespace(namespace);
        synchronized (ns) {
            if (!value.equals(ns.entries().put(key, value))) {
                ns.write();
            }
        }
    }

    @Override
    public void invalidate(String namespace, String key) {
        Objects.requireNonNull(key, "key");
        Namespace ns = namespace(namespace);
        synchronized (ns) {
            if (ns.entries().remove(key) != null) {
                ns.write();
            }
        }
    }

    private Namespace namespace(String name) {
        Objects.requireNonNull(name, "namespace");
        if (!NAMESPACE.matcher(name).matches()) {
            throw new IllegalArgumentException("namespace must match " + NAMESPACE + ": " + name);
        }
        return namespaces.computeIfAbsent(name, n -> new Namespace(dir.resolve(n + ".json")));
    }

    /** One namespace file and its in-memory copy. Guarded by its own monitor. */
    private static final class Namespace {
        private final Path file;
        private @Nullable Map<String, String> entries;

        Namespace(Path file) {
            this.file = file;
        }

        Map<String, String> entries() {
            if (entries == null) {
                entries = load(file);
            }
            return entries;
        }

        void write() {
            Map<String, String> snapshot = entries();
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
            Map<String, String> result = new HashMap<>();
            String content;
            try {
                content = Files.readString(file, StandardCharsets.UTF_8);
            } catch (NoSuchFileException e) {
                return result;
            } catch (IOException | RuntimeException e) {
                LOG.warn("cannot read cache file {}, starting empty: {}", file, e.toString());
                return result;
            }
            try {
                JsonNode root = JSON.readTree(content);
                if (root == null || !root.isObject()) {
                    LOG.warn("corrupt cache file {} (not a JSON object), starting empty", file);
                    return result;
                }
                for (Map.Entry<String, JsonNode> e : root.properties()) {
                    if (e.getValue().isTextual()) {
                        result.put(e.getKey(), e.getValue().asText());
                    }
                }
            } catch (IOException e) {
                LOG.warn("corrupt cache file {}, starting empty: {}", file, e.toString());
            }
            return result;
        }
    }
}
