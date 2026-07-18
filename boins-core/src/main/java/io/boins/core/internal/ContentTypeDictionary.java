package io.boins.core.internal;

import io.boins.core.StorageCorruptedException;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Persistent dictionary of content types: the id of a string is its ordinal in the file.
 *
 * <p>The file is a sequence of 2-byte-length-prefixed UTF-8 strings. The whole dictionary
 * is kept in memory (content types are few); lookups are O(1) both ways.</p>
 */
public final class ContentTypeDictionary implements Closeable {

    private final Path path;
    private final FileChannel channel;
    private final List<String> byId = new ArrayList<>();
    private final Map<String, Short> ids = new HashMap<>();
    private long fileSize;
    private volatile boolean dirty;

    ContentTypeDictionary(Path path) throws IOException, StorageCorruptedException {
        this.path = path;
        load();
        this.channel = FileChannel.open(path,
                StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        this.fileSize = channel.size();
    }

    /**
     * Returns the id of {@code contentType}, registering it if new.
     * Returns {@link IndexRecord#NO_CONTENT_TYPE} for null/empty input.
     */
    public synchronized short idOf(String contentType) throws IOException {
        if (contentType == null || contentType.isEmpty()) {
            return IndexRecord.NO_CONTENT_TYPE;
        }
        Short existing = ids.get(contentType);
        if (existing != null) {
            return existing;
        }
        if (byId.size() > Short.MAX_VALUE) {
            throw new IOException("Content type dictionary overflow (max " + Short.MAX_VALUE + " entries): " + path);
        }
        byte[] utf8 = contentType.getBytes(StandardCharsets.UTF_8);
        if (utf8.length > StringHeap.MAX_BYTES) {
            throw new IllegalArgumentException("Content type is too long: " + utf8.length + " bytes");
        }
        ByteBuffer buffer = ByteBuffer.allocate(Short.BYTES + utf8.length);
        buffer.putShort((short) utf8.length).put(utf8).flip();
        long written = 0L;
        while (buffer.hasRemaining()) {
            written += channel.write(buffer, fileSize + written);
        }
        fileSize += buffer.capacity();
        dirty = true;
        short id = (short) byId.size();
        byId.add(contentType);
        ids.put(contentType, id);
        return id;
    }

    /** Returns the content type for {@code id}, or {@code null} for a negative id. */
    public synchronized String byId(short id) {
        if (id < 0) {
            return null;
        }
        if (id >= byId.size()) {
            throw new IllegalArgumentException("Unknown content type id " + id + " (dictionary has "
                    + byId.size() + " entries): " + path);
        }
        return byId.get(id);
    }

    public synchronized int count() {
        return byId.size();
    }

    public void force() throws IOException {
        if (dirty) {
            dirty = false;
            channel.force(false);
        }
    }

    @Override
    public void close() throws IOException {
        if (channel.isOpen()) {
            force();
            channel.close();
        }
    }

    private void load() throws IOException, StorageCorruptedException {
        if (!Files.exists(path)) {
            return;
        }
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(path), 1 << 16))) {
            while (true) {
                int length;
                try {
                    length = in.readUnsignedShort();
                } catch (EOFException e) {
                    return;
                }
                byte[] utf8 = new byte[length];
                try {
                    in.readFully(utf8);
                } catch (EOFException e) {
                    throw new StorageCorruptedException("Truncated content type dictionary: " + path);
                }
                String value = new String(utf8, StandardCharsets.UTF_8);
                ids.putIfAbsent(value, (short) byId.size());
                byId.add(value);
            }
        }
    }
}
