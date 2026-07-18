package io.boins.core;

import io.boins.core.metrics.MetricsSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoinsTest {

    @TempDir
    Path tempDir;

    private Boins boins;

    @BeforeEach
    void setUp() throws Exception {
        boins = Boins.open(defaultOptions());
    }

    @AfterEach
    void tearDown() throws Exception {
        if (boins != null) {
            boins.close();
        }
    }

    private BoinsOptions defaultOptions() {
        return BoinsOptions.builder()
                .addRepository(tempDir.resolve("repo1"), 0L, BoinsOptions.DiskType.SSD)
                .blobFileLimit(20) // 1 MiB files for tests
                .minRepositoryFreeBytes(0L)
                .fsyncMode(BoinsOptions.FsyncMode.NEVER)
                .build();
    }

    private static byte[] randomBytes(int size) {
        byte[] bytes = new byte[size];
        new Random(size).nextBytes(bytes);
        return bytes;
    }

    private static String md5Hex(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(data));
    }

    private byte[] readAll(long blobId) throws Exception {
        try (InputStream in = boins.read(blobId)) {
            return in.readAllBytes();
        }
    }

    @Test
    void writeAndReadBytes() throws Exception {
        byte[] data = randomBytes(10_000);
        WriteResult result = boins.write(data, BlobMetadata.ofKey("test/blob-1"));
        assertEquals(10_000L, result.size());
        assertEquals(md5Hex(data), result.etag());
        assertArrayEquals(data, readAll(result.blobId()));
    }

    @Test
    void writeAndReadStream() throws Exception {
        byte[] data = randomBytes(70_000); // larger than one copy buffer
        WriteResult result = boins.write(new ByteArrayInputStream(data), data.length, BlobMetadata.EMPTY);
        assertArrayEquals(data, readAll(result.blobId()));
        assertEquals(md5Hex(data), result.etag());
    }

    @Test
    void writeAndReadFile() throws Exception {
        byte[] data = randomBytes(5_000);
        Path source = tempDir.resolve("source.bin");
        Files.write(source, data);
        WriteResult result = boins.write(source, new BlobMetadata("file.bin", "application/octet-stream"));
        assertArrayEquals(data, readAll(result.blobId()));
    }

    @Test
    void emptyBlob() throws Exception {
        WriteResult result = boins.write(new byte[0], BlobMetadata.ofKey("empty"));
        assertEquals(0L, result.size());
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", result.etag()); // MD5 of empty input
        assertArrayEquals(new byte[0], readAll(result.blobId()));
        BlobInfo info = boins.info(result.blobId());
        assertEquals("empty", info.key());
        assertEquals(0L, info.size());
    }

    @Test
    void rangeRead() throws Exception {
        byte[] data = "Hello, Boins range read!".getBytes(StandardCharsets.UTF_8);
        WriteResult result = boins.write(data, BlobMetadata.EMPTY);
        try (InputStream in = boins.read(result.blobId(), 7L, 5L)) {
            assertEquals("Boins", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        try (InputStream in = boins.read(result.blobId(), 0L, data.length)) {
            assertArrayEquals(data, in.readAllBytes());
        }
    }

    @Test
    void rangeReadOutOfBoundsFails() throws Exception {
        WriteResult result = boins.write(randomBytes(100), BlobMetadata.EMPTY);
        assertThrows(BoinsException.class, () -> boins.read(result.blobId(), 50L, 51L));
        assertThrows(BoinsException.class, () -> boins.read(result.blobId(), -1L, 10L));
    }

    @Test
    void metadataRoundTrip() throws Exception {
        BlobMetadata meta = new BlobMetadata("docs/report.pdf", "application/pdf",
                Map.of("author", "Алиса", "tag", "q3 report"));
        WriteResult result = boins.write(randomBytes(128), meta);
        BlobInfo info = boins.info(result.blobId());
        assertEquals("docs/report.pdf", info.key());
        assertEquals("application/pdf", info.contentType());
        assertEquals("Алиса", info.userMetadata().get("author"));
        assertEquals("q3 report", info.userMetadata().get("tag"));
        assertFalse(info.deleted());
        assertEquals(result.etag(), info.etag());
        assertEquals(0, info.partCount());
    }

    @Test
    void nullMetadataFields() throws Exception {
        WriteResult result = boins.write(randomBytes(16), BlobMetadata.EMPTY);
        BlobInfo info = boins.info(result.blobId());
        assertNull(info.key());
        assertNull(info.contentType());
        assertTrue(info.userMetadata().isEmpty());
    }

    @Test
    void multipartDigestOverride() throws Exception {
        byte[] data = randomBytes(1_000);
        byte[] fakeMd5 = randomBytes(16);
        WriteResult result = boins.write(new ByteArrayInputStream(data), data.length,
                BlobMetadata.ofKey("multi"), fakeMd5, 3);
        assertEquals(HexFormat.of().formatHex(fakeMd5) + "-3", result.etag());
        BlobInfo info = boins.info(result.blobId());
        assertEquals(3, info.partCount());
        assertArrayEquals(data, readAll(result.blobId()));
    }

    @Test
    void deleteAndReadFails() throws Exception {
        WriteResult result = boins.write(randomBytes(500), BlobMetadata.EMPTY);
        assertTrue(boins.delete(result.blobId()));
        assertFalse(boins.delete(result.blobId())); // idempotent
        assertThrows(BlobDeletedException.class, () -> boins.read(result.blobId()));
        BlobInfo info = boins.info(result.blobId()); // info still works
        assertTrue(info.deleted());
    }

    @Test
    void deleteBatchReportsFailures() throws Exception {
        WriteResult ok = boins.write(randomBytes(100), BlobMetadata.EMPTY);
        Map<Long, BoinsException> failures = boins.delete(List.of(ok.blobId(), 999_999L));
        assertEquals(1, failures.size());
        assertInstanceOf(BlobNotFoundException.class, failures.get(999_999L));
        assertTrue(boins.info(ok.blobId()).deleted());
    }

    @Test
    void unknownBlobIdFails() {
        assertThrows(BlobNotFoundException.class, () -> boins.read(123_456L));
        assertThrows(BlobNotFoundException.class, () -> boins.info(-1L));
        assertThrows(BlobNotFoundException.class, () -> boins.delete(42L));
    }

    @Test
    void cellReuseAfterDelete() throws Exception {
        byte[] first = randomBytes(4_096);
        WriteResult w1 = boins.write(first, BlobMetadata.EMPTY);
        long fileSizeAfterFirst = boins.state().repositories().getFirst().totalBlobBytes();
        assertTrue(boins.delete(w1.blobId()));

        byte[] second = randomBytes(4_096); // same size — must reuse the cell
        WriteResult w2 = boins.write(second, BlobMetadata.EMPTY);
        assertNotEquals(w1.blobId(), w2.blobId());
        assertArrayEquals(second, readAll(w2.blobId()));
        long fileSizeAfterSecond = boins.state().repositories().getFirst().totalBlobBytes();
        assertEquals(fileSizeAfterFirst, fileSizeAfterSecond, "blob file must not grow when a cell is reused");
    }

    @Test
    void failedStreamWriteReclaimsSpace() throws Exception {
        InputStream failing = new InputStream() {
            int produced;

            @Override
            public int read() {
                return produced++ < 100 ? 7 : -1; // claims 10_000 but delivers 100
            }
        };
        assertThrows(BoinsException.class,
                () -> boins.write(failing, 10_000L, BlobMetadata.EMPTY));
        // The reserved hole must be reclaimed by a following write of the same size.
        long fileSizeAfterFailure = boins.state().repositories().getFirst().totalBlobBytes();
        WriteResult recovered = boins.write(randomBytes(10_000), BlobMetadata.EMPTY);
        assertEquals(fileSizeAfterFailure, boins.state().repositories().getFirst().totalBlobBytes());
        assertArrayEquals(randomBytes(10_000), readAll(recovered.blobId()));
    }

    @Test
    void survivesRestart() throws Exception {
        byte[] data = randomBytes(2_048);
        BlobMetadata meta = new BlobMetadata("persist/me.bin", "text/plain", Map.of("k", "v"));
        WriteResult result = boins.write(data, meta);
        WriteResult deleted = boins.write(randomBytes(64), BlobMetadata.EMPTY);
        boins.delete(deleted.blobId());
        boins.close();

        boins = Boins.open(defaultOptions());
        assertArrayEquals(data, readAll(result.blobId()));
        BlobInfo info = boins.info(result.blobId());
        assertEquals("persist/me.bin", info.key());
        assertEquals("text/plain", info.contentType());
        assertEquals("v", info.userMetadata().get("k"));
        assertTrue(boins.info(deleted.blobId()).deleted());
    }

    @Test
    void blobTooLargeIsRejected() {
        // blobFileLimit is 2^20 = 1 MiB in tests
        assertThrows(BoinsException.class,
                () -> boins.write(new ByteArrayInputStream(new byte[0]), (1L << 20) + 1L, BlobMetadata.EMPTY));
    }

    @Test
    void negativeLengthIsRejected() {
        assertThrows(BoinsException.class,
                () -> boins.write(new ByteArrayInputStream(new byte[0]), -1L, BlobMetadata.EMPTY));
    }

    @Test
    void blobsSpillIntoNewBlobFiles() throws Exception {
        // 1 MiB limit per blob file; write 5 blobs of 600 KiB → at least 3 files.
        for (int i = 0; i < 5; i++) {
            boins.write(randomBytes(600 * 1024), BlobMetadata.ofKey("big-" + i));
        }
        RepositoryState state = boins.state().repositories().getFirst();
        assertTrue(state.blobFileCount() >= 3,
                "expected blobs to spill into several files, got " + state.blobFileCount());
        assertEquals(5L, state.blobCount());
    }

    @Test
    void multipleRepositoriesRouteByBlobId() throws Exception {
        boins.close();
        boins = Boins.open(BoinsOptions.builder()
                .addRepository(tempDir.resolve("multi-a"), 0L, BoinsOptions.DiskType.SSD)
                .addRepository(tempDir.resolve("multi-b"), 1_000_000L, BoinsOptions.DiskType.SSD)
                .blobFileLimit(20)
                .minRepositoryFreeBytes(0L)
                .fsyncMode(BoinsOptions.FsyncMode.NEVER)
                .freeCellsFile(tempDir.resolve("multi-free-cells.boins"))
                .metricsFile(tempDir.resolve("multi-metrics.boins"))
                .build());
        assertArrayEquals(new long[]{0L, 1_000_000L}, boins.blobIdOffsets());
        for (int i = 0; i < 20; i++) {
            byte[] data = randomBytes(100 + i);
            WriteResult r = boins.write(data, BlobMetadata.EMPTY);
            assertArrayEquals(data, readAll(r.blobId()));
        }
        assertEquals(2, boins.state().repositories().size());
    }

    @Test
    void metricsAccumulate() throws Exception {
        byte[] data = randomBytes(1_234);
        WriteResult r = boins.write(data, BlobMetadata.EMPTY);
        readAll(r.blobId());
        boins.delete(r.blobId());
        MetricsSnapshot m = boins.metrics();
        assertEquals(1_234L, m.bytesWritten());
        assertEquals(1_234L, m.bytesRead());
        assertEquals(1L, m.blobsWritten());
        assertEquals(1L, m.blobsRead());
        assertEquals(1L, m.blobsDeleted());
    }

    @Test
    void metricsSurviveRestart() throws Exception {
        boins.write(randomBytes(500), BlobMetadata.EMPTY);
        boins.close();
        boins = Boins.open(defaultOptions());
        assertEquals(500L, boins.metrics().bytesWritten());
        boins.write(randomBytes(300), BlobMetadata.EMPTY);
        assertEquals(800L, boins.metrics().bytesWritten());
    }

    @Test
    void operationsAfterCloseFail() throws Exception {
        boins.close();
        assertThrows(BoinsException.class, () -> boins.write(new byte[1], BlobMetadata.EMPTY));
        assertThrows(BoinsException.class, () -> boins.read(0L));
        Boins closed = boins;
        boins = null;
        closed.close(); // second close is a no-op
    }

    @Test
    void concurrentWritesAndReads() throws Exception {
        int threads = 8;
        int blobsPerThread = 25;
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<Void>> futures = pool.invokeAll(java.util.Collections.nCopies(threads, () -> {
                for (int i = 0; i < blobsPerThread; i++) {
                    int size = 512 + ThreadLocalRandom.current().nextInt(4_096);
                    byte[] data = new byte[size];
                    ThreadLocalRandom.current().nextBytes(data);
                    WriteResult r = boins.write(data, BlobMetadata.EMPTY);
                    byte[] readBack = readAll(r.blobId());
                    assertArrayEquals(data, readBack);
                    if (size % 3 == 0) {
                        boins.delete(r.blobId());
                    }
                }
                return null;
            }));
            for (Future<Void> f : futures) {
                f.get();
            }
        }
        Set<Long> seen = new java.util.HashSet<>();
        RepositoryState state = boins.state().repositories().getFirst();
        assertEquals(threads * blobsPerThread, state.blobCount());
        for (long id = 0; id < state.blobCount(); id++) {
            assertTrue(seen.add(boins.info(id).id()));
        }
    }

    @Test
    void stateReportsRepositoryDetails() throws Exception {
        boins.write(randomBytes(256), BlobMetadata.ofKey("state-test"));
        BoinsState state = boins.state();
        RepositoryState repo = state.repositories().getFirst();
        assertEquals(1L, repo.blobCount());
        assertEquals(0L, repo.maxBlobId());
        assertTrue(repo.indexFileSize() > 0L);
        assertTrue(repo.heapFileSize() > 0L);
        assertTrue(state.freeCells().trackedCells() == 0L);
    }
}
