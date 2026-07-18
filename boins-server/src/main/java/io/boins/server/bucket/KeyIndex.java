package io.boins.server.bucket;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.Map;
import java.util.NavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.CRC32;

/**
 * Persistent object-key → blob-id map of one bucket (bitcask style).
 *
 * <p>On disk: an append-only log of records
 * {@code crc32(4) op(1) keyLen(2) keyUtf8 blobId(8)} where op is PUT(1) or DELETE(2).
 * The full map lives in memory (a concurrent skip list, which also serves sorted
 * ListObjects scans). On startup the log is replayed; a torn tail (crash during append)
 * is detected by CRC and truncated.</p>
 *
 * <p>When the log accumulates more than {@value #COMPACT_MIN_RECORDS} records and over
 * half of them are dead, it is compacted by atomically replacing the file with one
 * containing only live entries.</p>
 */
public final class KeyIndex implements Closeable {

    private static final byte OP_PUT = 1;
    private static final byte OP_DELETE = 2;
    private static final int COMPACT_MIN_RECORDS = 4_096;
    /** S3 limits keys to 1024 bytes; we allow the technical maximum of the format. */
    public static final int MAX_KEY_BYTES = 65_535;

    private final Path file;
    private final ConcurrentSkipListMap<String, Long> map = new ConcurrentSkipListMap<>();
    private final ReentrantLock writeLock = new ReentrantLock();
    private FileChannel channel;
    private long filePosition;
    private long totalRecords;
    private volatile boolean dirty;

    public static KeyIndex open(Path file) throws IOException {
        return new KeyIndex(file);
    }

    private KeyIndex(Path file) throws IOException {
        this.file = file;
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        long validBytes = replay();
        this.channel = FileChannel.open(file,
                StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        if (channel.size() > validBytes) {
            // Torn tail after a crash: drop the unparseable suffix.
            channel.truncate(validBytes);
        }
        this.filePosition = validBytes;
    }

    /** Blob id for {@code key}, or {@code null} if absent. */
    public Long get(String key) {
        return map.get(key);
    }

    /** Number of live keys. */
    public int size() {
        return map.size();
    }

    /** Read-only sorted view for ListObjects scans. */
    public NavigableMap<String, Long> sortedView() {
        return Collections.unmodifiableNavigableMap(map);
    }

    /** Maps {@code key} to {@code blobId}; returns the previous blob id or {@code null}. */
    public Long put(String key, long blobId) throws IOException {
        checkKey(key);
        writeLock.lock();
        try {
            append(OP_PUT, key, blobId);
            Long previous = map.put(key, blobId);
            maybeCompact();
            return previous;
        } finally {
            writeLock.unlock();
        }
    }

    /** Removes {@code key}; returns the previous blob id or {@code null} if it was absent. */
    public Long remove(String key) throws IOException {
        checkKey(key);
        writeLock.lock();
        try {
            Long previous = map.remove(key);
            if (previous != null) {
                append(OP_DELETE, key, previous);
                maybeCompact();
            }
            return previous;
        } finally {
            writeLock.unlock();
        }
    }

    public void force() throws IOException {
        if (dirty) {
            writeLock.lock();
            try {
                dirty = false;
                channel.force(false);
            } finally {
                writeLock.unlock();
            }
        }
    }

    @Override
    public void close() throws IOException {
        writeLock.lock();
        try {
            if (channel.isOpen()) {
                channel.force(false);
                channel.close();
            }
        } finally {
            writeLock.unlock();
        }
    }

    // ---------------------------------------------------------------- internals

    private static void checkKey(String key) {
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("Object key must not be empty");
        }
        if (key.getBytes(StandardCharsets.UTF_8).length > MAX_KEY_BYTES) {
            throw new IllegalArgumentException("Object key is too long");
        }
    }

    private void append(byte op, String key, long blobId) throws IOException {
        byte[] record = encode(op, key, blobId);
        ByteBuffer buffer = ByteBuffer.wrap(record);
        while (buffer.hasRemaining()) {
            filePosition += channel.write(buffer, filePosition);
        }
        totalRecords++;
        dirty = true;
    }

    private static byte[] encode(byte op, String key, long blobId) {
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        ByteBuffer payload = ByteBuffer.allocate(1 + 2 + keyBytes.length + 8);
        payload.put(op).putShort((short) keyBytes.length).put(keyBytes).putLong(blobId);
        CRC32 crc = new CRC32();
        crc.update(payload.array(), 0, payload.capacity());
        ByteBuffer record = ByteBuffer.allocate(4 + payload.capacity());
        record.putInt((int) crc.getValue()).put(payload.array());
        return record.array();
    }

    /** Replays the log into the in-memory map; returns the length of the valid prefix. */
    private long replay() throws IOException {
        if (!Files.exists(file)) {
            return 0L;
        }
        long validBytes = 0L;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file), 1 << 16))) {
            while (true) {
                int storedCrc;
                try {
                    storedCrc = in.readInt();
                } catch (EOFException e) {
                    break;
                }
                byte op;
                int keyLen;
                byte[] keyBytes;
                long blobId;
                try {
                    op = in.readByte();
                    keyLen = in.readUnsignedShort();
                    keyBytes = new byte[keyLen];
                    in.readFully(keyBytes);
                    blobId = in.readLong();
                } catch (EOFException e) {
                    break; // torn tail
                }
                ByteBuffer payload = ByteBuffer.allocate(1 + 2 + keyLen + 8);
                payload.put(op).putShort((short) keyLen).put(keyBytes).putLong(blobId);
                CRC32 crc = new CRC32();
                crc.update(payload.array(), 0, payload.capacity());
                if ((int) crc.getValue() != storedCrc) {
                    break; // torn tail
                }
                String key = new String(keyBytes, StandardCharsets.UTF_8);
                if (op == OP_PUT) {
                    map.put(key, blobId);
                } else if (op == OP_DELETE) {
                    map.remove(key);
                } else {
                    break; // unknown op: treat as corruption boundary
                }
                totalRecords++;
                validBytes += 4 + 1 + 2 + keyLen + 8;
            }
        }
        return validBytes;
    }

    /** Rewrites the log with only live entries when it is dominated by dead records. */
    private void maybeCompact() throws IOException {
        if (totalRecords < COMPACT_MIN_RECORDS || totalRecords < 2L * map.size()) {
            return;
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".compact");
        try (OutputStream fileOut = Files.newOutputStream(tmp,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
             DataOutputStream out = new DataOutputStream(new java.io.BufferedOutputStream(fileOut, 1 << 16))) {
            for (Map.Entry<String, Long> e : map.entrySet()) {
                out.write(encode(OP_PUT, e.getKey(), e.getValue()));
            }
        }
        channel.close();
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        channel = FileChannel.open(file,
                StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        channel.force(false);
        filePosition = channel.size();
        totalRecords = map.size();
        dirty = false;
    }
}
