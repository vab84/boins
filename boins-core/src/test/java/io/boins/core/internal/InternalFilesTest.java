package io.boins.core.internal;

import io.boins.core.BlobNotFoundException;
import io.boins.core.BoinsOptions;
import io.boins.core.StorageCorruptedException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InternalFilesTest {

    @TempDir
    Path tempDir;

    @Nested
    class IndexFileTests {

        @Test
        void insertReadRoundTrip() throws Exception {
            try (IndexFile index = new IndexFile(tempDir.resolve("index.boins"), 100L)) {
                IndexRecord record = new IndexRecord((short) 2, 4_096L, 1_000L, 1_024L,
                        123_456L, IndexRecord.ALIVE, 55L, 77L, (short) 3, new byte[16], 0);
                long blobId = index.insert(record);
                assertEquals(100L, blobId);
                IndexRecord read = index.read(blobId);
                assertEquals(record.blobPosition(), read.blobPosition());
                assertEquals(record.blobSize(), read.blobSize());
                assertEquals(record.cellSize(), read.cellSize());
                assertEquals(record.keyPosition(), read.keyPosition());
                assertEquals(record.metaPosition(), read.metaPosition());
                assertEquals(record.contentTypeId(), read.contentTypeId());
                assertEquals(101L, index.nextBlobId());
                assertEquals(100L, index.maxBlobId());
                assertEquals(IndexRecord.SIZE, index.sizeBytes());
            }
        }

        @Test
        void markDeletedReturnsCellSize() throws Exception {
            try (IndexFile index = new IndexFile(tempDir.resolve("index.boins"), 0L)) {
                long id = index.insert(new IndexRecord((short) 0, 0L, 500L, 512L,
                        1L, IndexRecord.ALIVE, -1L, -1L, (short) -1, null, IndexRecord.NO_DIGEST));
                long cellSize = index.markDeleted(id, 42L);
                assertEquals(512L, cellSize);
                assertEquals(42L, index.read(id).deleteTime());
                assertTrue(index.read(id).deleted());
            }
        }

        @Test
        void markCellConsumedZeroesCellSize() throws Exception {
            try (IndexFile index = new IndexFile(tempDir.resolve("index.boins"), 0L)) {
                long id = index.insert(new IndexRecord((short) 0, 0L, 500L, 512L,
                        1L, 99L, -1L, -1L, (short) -1, null, IndexRecord.NO_DIGEST));
                index.markCellConsumed(id);
                assertEquals(0L, index.read(id).cellSize());
            }
        }

        @Test
        void outOfRangeIdsRejected() throws Exception {
            try (IndexFile index = new IndexFile(tempDir.resolve("index.boins"), 10L)) {
                assertThrows(BlobNotFoundException.class, () -> index.read(9L));
                assertThrows(BlobNotFoundException.class, () -> index.read(10L));
                assertEquals(-1L, index.maxBlobId());
            }
        }

        @Test
        void invalidFileSizeDetected() throws Exception {
            Path file = tempDir.resolve("index.boins");
            Files.write(file, new byte[IndexRecord.SIZE - 1]);
            assertThrows(StorageCorruptedException.class, () -> new IndexFile(file, 0L));
        }

        @Test
        void holeRecordDetectedOnRead() throws Exception {
            Path file = tempDir.resolve("index.boins");
            Files.write(file, new byte[IndexRecord.SIZE]); // an all-zeros record
            try (IndexFile index = new IndexFile(file, 0L)) {
                assertThrows(StorageCorruptedException.class, () -> index.read(0L));
            }
        }
    }

    @Nested
    class StringHeapTests {

        @Test
        void addAndReadStrings() throws Exception {
            try (StringHeap heap = new StringHeap(tempDir.resolve("heap.boins"))) {
                long p1 = heap.add("hello");
                long p2 = heap.add("мир/детали.txt");
                assertEquals("hello", heap.read(p1));
                assertEquals("мир/детали.txt", heap.read(p2));
                assertNull(heap.read(-1L));
                assertEquals(IndexRecord.NO_HEAP_POSITION, heap.add(null));
                assertEquals(IndexRecord.NO_HEAP_POSITION, heap.add(""));
            }
        }

        @Test
        void tooLongStringRejected() throws Exception {
            try (StringHeap heap = new StringHeap(tempDir.resolve("heap.boins"))) {
                assertThrows(IllegalArgumentException.class, () -> heap.add("x".repeat(StringHeap.MAX_BYTES + 1)));
            }
        }

        @Test
        void invalidPositionRejected() throws Exception {
            try (StringHeap heap = new StringHeap(tempDir.resolve("heap.boins"))) {
                heap.add("data");
                assertThrows(IOException.class, () -> heap.read(1_000_000L));
            }
        }

        @Test
        void persistsAcrossReopen() throws Exception {
            long position;
            try (StringHeap heap = new StringHeap(tempDir.resolve("heap.boins"))) {
                position = heap.add("persistent value");
            }
            try (StringHeap heap = new StringHeap(tempDir.resolve("heap.boins"))) {
                assertEquals("persistent value", heap.read(position));
            }
        }
    }

    @Nested
    class ContentTypeDictionaryTests {

        @Test
        void idsAreStable() throws Exception {
            try (ContentTypeDictionary dict = new ContentTypeDictionary(tempDir.resolve("ct.boins"))) {
                short a = dict.idOf("text/plain");
                short b = dict.idOf("image/png");
                assertEquals(a, dict.idOf("text/plain"));
                assertEquals("text/plain", dict.byId(a));
                assertEquals("image/png", dict.byId(b));
                assertEquals(2, dict.count());
                assertEquals(IndexRecord.NO_CONTENT_TYPE, dict.idOf(null));
                assertNull(dict.byId((short) -1));
            }
        }

        @Test
        void persistsAcrossReopen() throws Exception {
            short id;
            try (ContentTypeDictionary dict = new ContentTypeDictionary(tempDir.resolve("ct.boins"))) {
                dict.idOf("application/json");
                id = dict.idOf("video/mp4");
            }
            try (ContentTypeDictionary dict = new ContentTypeDictionary(tempDir.resolve("ct.boins"))) {
                assertEquals("video/mp4", dict.byId(id));
                assertEquals(id, dict.idOf("video/mp4"));
                assertEquals(2, dict.count());
            }
        }

        @Test
        void unknownIdRejected() throws Exception {
            try (ContentTypeDictionary dict = new ContentTypeDictionary(tempDir.resolve("ct.boins"))) {
                assertThrows(IllegalArgumentException.class, () -> dict.byId((short) 5));
            }
        }
    }

    @Nested
    class BlobFileTests {

        @Test
        void concurrentRegionsDoNotOverlap() throws Exception {
            try (BlobFile bf = new BlobFile(tempDir.resolve("blob.0.boins"), (short) 0, BoinsOptions.DiskType.SSD)) {
                long p1 = bf.reserve(100L);
                long p2 = bf.reserve(200L);
                assertEquals(0L, p1);
                assertEquals(100L, p2);
                assertEquals(300L, bf.size());
                byte[] a = "a".repeat(100).getBytes(StandardCharsets.UTF_8);
                byte[] b = "b".repeat(200).getBytes(StandardCharsets.UTF_8);
                bf.write(p2, new ByteArrayInputStream(b), 200L, null);
                bf.write(p1, new ByteArrayInputStream(a), 100L, null);
                try (InputStream in = bf.openStream(p1, 100L)) {
                    assertArrayEquals(a, in.readAllBytes());
                }
                try (InputStream in = bf.openStream(p2, 200L)) {
                    assertArrayEquals(b, in.readAllBytes());
                }
            }
        }

        @Test
        void truncatedSourceStreamFails() throws Exception {
            try (BlobFile bf = new BlobFile(tempDir.resolve("blob.0.boins"), (short) 0, BoinsOptions.DiskType.HDD)) {
                long position = bf.reserve(100L);
                assertThrows(IOException.class,
                        () -> bf.write(position, new ByteArrayInputStream(new byte[10]), 100L, null));
            }
        }
    }

    @Nested
    class ChannelRangeInputStreamTests {

        @Test
        void readsExactRange() throws Exception {
            Path file = tempDir.resolve("data.bin");
            Files.writeString(file, "0123456789");
            try (var channel = java.nio.channels.FileChannel.open(file, StandardOpenOption.READ)) {
                try (InputStream in = new ChannelRangeInputStream(channel, 2L, 5L)) {
                    assertEquals("23456", new String(in.readAllBytes(), StandardCharsets.UTF_8));
                }
                try (InputStream in = new ChannelRangeInputStream(channel, 0L, 10L)) {
                    assertEquals('0', in.read());
                    assertEquals(3L, in.skip(3L));
                    assertEquals('4', in.read());
                    assertEquals(5, in.available());
                }
            }
        }

        @Test
        void rangeBeyondFileEndFails() throws Exception {
            Path file = tempDir.resolve("data.bin");
            Files.writeString(file, "0123");
            try (var channel = java.nio.channels.FileChannel.open(file, StandardOpenOption.READ);
                 InputStream in = new ChannelRangeInputStream(channel, 2L, 10L)) {
                assertThrows(IOException.class, in::readAllBytes);
            }
        }
    }

    @Nested
    class UserMetaCodecTests {

        @Test
        void roundTrip() {
            Map<String, String> meta = Map.of("key with spaces", "значение & спецсимволы = да");
            String encoded = BoinsImpl.encodeUserMeta(meta);
            assertEquals(meta, BoinsImpl.decodeUserMeta(encoded));
        }

        @Test
        void emptyAndNull() {
            assertNull(BoinsImpl.encodeUserMeta(Map.of()));
            assertNull(BoinsImpl.encodeUserMeta(null));
            assertEquals(Map.of(), BoinsImpl.decodeUserMeta(null));
            assertEquals(Map.of(), BoinsImpl.decodeUserMeta(""));
        }
    }

    @Nested
    class RepositoryTests {

        @Test
        void manifestOffsetMismatchDetected() throws Exception {
            var options = new BoinsOptions.RepositoryOptions(tempDir.resolve("repo"), 0L, BoinsOptions.DiskType.SSD);
            new Repository(options, 1L << 20, 1_024L).close();
            var changed = new BoinsOptions.RepositoryOptions(tempDir.resolve("repo"), 500L, BoinsOptions.DiskType.SSD);
            assertThrows(StorageCorruptedException.class, () -> new Repository(changed, 1L << 20, 1_024L));
        }

        @Test
        void appendSelectionCreatesNewFileWhenFull() throws Exception {
            var options = new BoinsOptions.RepositoryOptions(tempDir.resolve("repo2"), 0L, BoinsOptions.DiskType.SSD);
            try (Repository repo = new Repository(options, 1_000L, 100L)) {
                BlobFile first = repo.blobFileForAppend(900L);
                first.reserve(900L);
                BlobFile second = repo.blobFileForAppend(200L);
                assertTrue(first.index() != second.index(), "a full file must not be selected again");
            }
        }
    }
}
