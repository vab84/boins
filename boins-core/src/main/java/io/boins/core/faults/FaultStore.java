package io.boins.core.faults;

import java.io.Closeable;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Persists exceptional events as flat text files in a single directory.
 *
 * <p>Each unique fault gets one report file. Uniqueness (deduplication) is defined by the
 * exception type plus the first {@code stackFrames} stack trace lines. Repeated occurrences
 * of the same fault only bump an in-memory counter; the report file is rewritten at most
 * once per {@code cooldownMillis} (rate limiting), and once more on {@link #close()}.</p>
 *
 * <p>Listeners registered via {@link #addListener(Consumer)} receive a {@link FaultEvent}
 * for <em>every</em> occurrence, including rate-limited ones — this is the callback channel
 * used by the admin interface.</p>
 *
 * <p>{@link #record} never throws: a fault store must not create new faults. Unwritable
 * reports fall back to stderr.</p>
 */
public final class FaultStore implements Closeable {

    /** Default number of stack trace lines in the deduplication key. */
    public static final int DEFAULT_STACK_FRAMES = 5;
    /** Default minimum interval between rewrites of the same report file. */
    public static final long DEFAULT_COOLDOWN_MILLIS = 60_000L;
    /** Default cap on the number of report files; the least recently seen fault is evicted. */
    public static final int DEFAULT_MAX_FILES = 1_000;

    private static final String FILE_SUFFIX = ".txt";

    private final Path dir;
    private final int stackFrames;
    private final long cooldownMillis;
    private final int maxFiles;
    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Consumer<FaultEvent>> listeners = new CopyOnWriteArrayList<>();
    private volatile boolean closed;

    private FaultStore(Path dir, int stackFrames, long cooldownMillis, int maxFiles) throws IOException {
        this.dir = dir;
        this.stackFrames = stackFrames;
        this.cooldownMillis = cooldownMillis;
        this.maxFiles = maxFiles;
        Files.createDirectories(dir);
        loadExisting();
    }

    /** Opens a fault store with default settings. */
    public static FaultStore open(Path dir) throws IOException {
        return open(dir, DEFAULT_STACK_FRAMES, DEFAULT_COOLDOWN_MILLIS, DEFAULT_MAX_FILES);
    }

    /**
     * Opens a fault store.
     *
     * @param dir            directory for report files; created if missing
     * @param stackFrames    number of top stack trace lines in the deduplication key
     * @param cooldownMillis minimum interval between rewrites of one report file
     * @param maxFiles       cap on report files; least recently seen fault is evicted
     */
    public static FaultStore open(Path dir, int stackFrames, long cooldownMillis, int maxFiles) throws IOException {
        Objects.requireNonNull(dir, "dir");
        if (stackFrames < 0) {
            throw new IllegalArgumentException("stackFrames must be >= 0");
        }
        if (cooldownMillis < 0L) {
            throw new IllegalArgumentException("cooldownMillis must be >= 0");
        }
        if (maxFiles < 1) {
            throw new IllegalArgumentException("maxFiles must be >= 1");
        }
        return new FaultStore(dir, stackFrames, cooldownMillis, maxFiles);
    }

    public Path dir() {
        return dir;
    }

    public void addListener(Consumer<FaultEvent> listener) {
        listeners.add(Objects.requireNonNull(listener));
    }

    public void removeListener(Consumer<FaultEvent> listener) {
        listeners.remove(listener);
    }

    /** Records an exception without extra context. */
    public void record(Throwable throwable) {
        record(throwable, Map.of());
    }

    /**
     * Records an exception with context (e.g. HTTP request details).
     * Never throws.
     */
    public void record(Throwable throwable, Map<String, String> context) {
        if (throwable == null) {
            return;
        }
        try {
            long now = System.currentTimeMillis();
            String hash = dedupHash(throwable);
            Entry entry = entries.get(hash);
            if (entry == null) {
                // Eviction must happen outside computeIfAbsent: its mapping function
                // is not allowed to modify the map.
                evictIfNeeded();
                entry = entries.computeIfAbsent(hash,
                        h -> new Entry(h, throwable.getClass().getName(), now, fileNameFor(throwable, h)));
            }
            long occurrences = entry.count.incrementAndGet();
            entry.lastSeen = now;
            entry.lastMessage = firstLine(throwable.getMessage());
            boolean persisted = false;
            if (occurrences == 1L || (!closed && now - entry.lastPersist >= cooldownMillis)) {
                persisted = writeReport(entry, throwable, context, now);
            } else {
                entry.dirty = true;
            }
            notifyListeners(new FaultEvent(throwable, context, now, hash, occurrences, persisted));
        } catch (RuntimeException e) {
            // Last resort: the fault store itself must never fail the caller.
            throwable.printStackTrace();
            e.printStackTrace();
        }
    }

    /** Summaries of all tracked faults, most recent first. */
    public List<FaultSummary> list() {
        List<FaultSummary> result = new ArrayList<>(entries.size());
        for (Entry e : entries.values()) {
            result.add(new FaultSummary(e.hash, e.type, e.lastMessage, e.count.get(), e.firstSeen, e.lastSeen, e.fileName));
        }
        result.sort(Comparator.comparingLong(FaultSummary::lastSeen).reversed());
        return result;
    }

    /** Full text of one report file, or {@code null} if unknown. */
    public String reportText(String dedupHash) throws IOException {
        Entry e = entries.get(dedupHash);
        if (e == null) {
            return null;
        }
        Path file = dir.resolve(e.fileName);
        return Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : null;
    }

    /** Persists pending counter updates for faults whose rewrite was rate-limited. */
    @Override
    public void close() {
        closed = true;
        for (Entry e : entries.values()) {
            if (e.dirty) {
                writeCounterUpdate(e);
            }
        }
    }

    // ---------------------------------------------------------------- internals

    private void notifyListeners(FaultEvent event) {
        for (Consumer<FaultEvent> listener : listeners) {
            try {
                listener.accept(event);
            } catch (RuntimeException e) {
                e.printStackTrace();
            }
        }
    }

    private String dedupHash(Throwable t) {
        StringBuilder key = new StringBuilder(256);
        key.append(t.getClass().getName());
        StackTraceElement[] trace = t.getStackTrace();
        for (int i = 0; i < Math.min(stackFrames, trace.length); i++) {
            key.append('\n').append(trace[i]);
        }
        return sha256Hex16(key.toString());
    }

    private static String sha256Hex16(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String fileNameFor(Throwable t, String hash) {
        String simple = t.getClass().getSimpleName();
        if (simple.isEmpty()) {
            simple = "Throwable";
        }
        return simple + "-" + hash + FILE_SUFFIX;
    }

    private static String firstLine(String s) {
        if (s == null) {
            return null;
        }
        int i = s.indexOf('\n');
        String line = i < 0 ? s : s.substring(0, i);
        return line.strip();
    }

    private synchronized boolean writeReport(Entry entry, Throwable t, Map<String, String> context, long now) {
        try {
            StringBuilder sb = new StringBuilder(2048);
            sb.append("type: ").append(entry.type).append('\n');
            sb.append("hash: ").append(entry.hash).append('\n');
            sb.append("count: ").append(entry.count.get()).append('\n');
            sb.append("firstSeenMillis: ").append(entry.firstSeen).append('\n');
            sb.append("lastSeenMillis: ").append(now).append('\n');
            sb.append("firstSeen: ").append(Instant.ofEpochMilli(entry.firstSeen)).append('\n');
            sb.append("lastSeen: ").append(Instant.ofEpochMilli(now)).append('\n');
            String message = firstLine(t.getMessage());
            if (message != null) {
                sb.append("message: ").append(message).append('\n');
            }
            if (!context.isEmpty()) {
                sb.append("\n--- context ---\n");
                context.forEach((k, v) -> sb.append(k).append(": ").append(v).append('\n'));
            }
            sb.append("\n--- stack trace ---\n").append(stackTraceOf(t));
            Files.writeString(dir.resolve(entry.fileName), sb.toString(), StandardCharsets.UTF_8);
            entry.lastPersist = now;
            entry.dirty = false;
            return true;
        } catch (IOException e) {
            t.printStackTrace();
            e.printStackTrace();
            return false;
        }
    }

    /** Rewrites only the header counters of an existing report, keeping its trace sections. */
    private synchronized void writeCounterUpdate(Entry entry) {
        Path file = dir.resolve(entry.fileName);
        try {
            if (!Files.exists(file)) {
                return;
            }
            String text = Files.readString(file, StandardCharsets.UTF_8);
            text = replaceHeader(text, "count", Long.toString(entry.count.get()));
            text = replaceHeader(text, "lastSeenMillis", Long.toString(entry.lastSeen));
            text = replaceHeader(text, "lastSeen", Instant.ofEpochMilli(entry.lastSeen).toString());
            Files.writeString(file, text, StandardCharsets.UTF_8);
            entry.dirty = false;
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static String replaceHeader(String text, String key, String value) {
        int start = text.indexOf(key + ": ");
        if (start != 0 && (start < 0 || text.charAt(start - 1) != '\n')) {
            return text;
        }
        int end = text.indexOf('\n', start);
        if (end < 0) {
            end = text.length();
        }
        return text.substring(0, start) + key + ": " + value + text.substring(end);
    }

    private static String stackTraceOf(Throwable t) {
        StringWriter sw = new StringWriter(1024);
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    private void evictIfNeeded() {
        while (entries.size() >= maxFiles) {
            Entry oldest = null;
            for (Entry e : entries.values()) {
                if (oldest == null || e.lastSeen < oldest.lastSeen) {
                    oldest = e;
                }
            }
            if (oldest == null || !entries.remove(oldest.hash, oldest)) {
                return;
            }
            try {
                Files.deleteIfExists(dir.resolve(oldest.fileName));
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void loadExisting() throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(f -> f.getFileName().toString().endsWith(FILE_SUFFIX))
                    .forEach(this::loadReport);
        }
    }

    private void loadReport(Path file) {
        try {
            Map<String, String> headers = parseHeaders(Files.readString(file, StandardCharsets.UTF_8));
            String hash = headers.get("hash");
            String type = headers.get("type");
            if (hash == null || type == null) {
                return; // Not a Boins report; leave the file alone.
            }
            long firstSeen = parseLong(headers.get("firstSeenMillis"), System.currentTimeMillis());
            Entry entry = new Entry(hash, type, firstSeen, file.getFileName().toString());
            entry.count.set(parseLong(headers.get("count"), 1L));
            entry.lastSeen = parseLong(headers.get("lastSeenMillis"), firstSeen);
            entry.lastMessage = headers.get("message");
            entry.lastPersist = entry.lastSeen;
            entries.putIfAbsent(hash, entry);
        } catch (IOException | RuntimeException e) {
            e.printStackTrace();
        }
    }

    private static Map<String, String> parseHeaders(String text) {
        Map<String, String> headers = new java.util.HashMap<>();
        for (String line : text.lines().toList()) {
            if (line.isBlank()) {
                break;
            }
            int colon = line.indexOf(": ");
            if (colon > 0) {
                headers.put(line.substring(0, colon), line.substring(colon + 2));
            }
        }
        return headers;
    }

    private static long parseLong(String s, long fallback) {
        if (s == null) {
            return fallback;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static final class Entry {
        final String hash;
        final String type;
        final long firstSeen;
        final String fileName;
        final AtomicLong count = new AtomicLong();
        volatile long lastSeen;
        volatile long lastPersist;
        volatile String lastMessage;
        volatile boolean dirty;

        Entry(String hash, String type, long firstSeen, String fileName) {
            this.hash = hash;
            this.type = type;
            this.firstSeen = firstSeen;
            this.fileName = fileName;
            this.lastSeen = firstSeen;
        }
    }
}
