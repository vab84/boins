package io.boins.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Embedded-mode throughput benchmark. Opt-in (not a correctness test):
 *
 * <pre>./gradlew :boins-core:test --tests "io.boins.core.CoreBenchmark" -Dboins.bench=true</pre>
 *
 * <p>Data volumes are deliberately modest (&lt; 2 GiB total) so the benchmark is safe to
 * run on a laptop with limited disk space.</p>
 */
@EnabledIfSystemProperty(named = "boins.bench", matches = "true")
class CoreBenchmark {

    private static final int KIB = 1024;
    private static final int MIB = 1024 * 1024;

    @TempDir
    Path tempDir;

    @Test
    void throughput() throws Exception {
        System.out.println();
        System.out.println("=== Boins core benchmark (" + osSummary() + ") ===");

        try (Boins boins = open(BoinsOptions.FsyncMode.NEVER, "bench-never")) {
            benchWrites(boins, "write  4 KiB blobs, fsync=NEVER ", 4 * KIB, 5_000);
            benchWrites(boins, "write 64 KiB blobs, fsync=NEVER ", 64 * KIB, 2_000);
            long[] ids = benchWrites(boins, "write  1 MiB blobs, fsync=NEVER ", MIB, 500);
            benchReads(boins, "read   1 MiB blobs, sequential  ", ids, MIB);
            benchRandomReads(boins, "read   1 MiB blobs, random      ", ids, MIB);
            benchConcurrentWrites(boins, "write 64 KiB blobs, 8 threads   ", 64 * KIB, 2_000);
            benchReuse(boins);
        }
        try (Boins boins = open(BoinsOptions.FsyncMode.INTERVAL, "bench-interval")) {
            benchWrites(boins, "write 64 KiB blobs, fsync=1s    ", 64 * KIB, 2_000);
        }
        try (Boins boins = open(BoinsOptions.FsyncMode.ALWAYS, "bench-always")) {
            benchWrites(boins, "write 64 KiB blobs, fsync=ALWAYS", 64 * KIB, 200);
        }
        System.out.println("=== end of benchmark ===");
    }

    private Boins open(BoinsOptions.FsyncMode fsyncMode, String dir) throws BoinsException {
        return Boins.open(BoinsOptions.builder()
                .addRepository(tempDir.resolve(dir), 0L, BoinsOptions.DiskType.SSD)
                .blobFileLimit(30) // 1 GiB blob files
                .minRepositoryFreeBytes(0L)
                .fsyncMode(fsyncMode)
                .build());
    }

    private long[] benchWrites(Boins boins, String name, int blobSize, int count) throws Exception {
        byte[] data = new byte[blobSize];
        new Random(blobSize).nextBytes(data);
        long[] ids = new long[count];
        long start = System.nanoTime();
        for (int i = 0; i < count; i++) {
            ids[i] = boins.write(data, BlobMetadata.EMPTY).blobId();
        }
        report(name, (long) blobSize * count, count, System.nanoTime() - start);
        return ids;
    }

    private void benchReads(Boins boins, String name, long[] ids, int blobSize) throws Exception {
        byte[] buffer = new byte[64 * KIB];
        long start = System.nanoTime();
        for (long id : ids) {
            try (InputStream in = boins.read(id)) {
                while (in.read(buffer) >= 0) {
                    // drain
                }
            }
        }
        report(name, (long) blobSize * ids.length, ids.length, System.nanoTime() - start);
    }

    private void benchRandomReads(Boins boins, String name, long[] ids, int blobSize) throws Exception {
        byte[] buffer = new byte[64 * KIB];
        Random random = new Random(99L);
        int count = ids.length;
        long start = System.nanoTime();
        for (int i = 0; i < count; i++) {
            long id = ids[random.nextInt(ids.length)];
            try (InputStream in = boins.read(id)) {
                while (in.read(buffer) >= 0) {
                    // drain
                }
            }
        }
        report(name, (long) blobSize * count, count, System.nanoTime() - start);
    }

    private void benchConcurrentWrites(Boins boins, String name, int blobSize, int totalCount) throws Exception {
        int threads = 8;
        int perThread = totalCount / threads;
        byte[] data = new byte[blobSize];
        new Random(7L).nextBytes(data);
        long start = System.nanoTime();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Callable<Void>> jobs = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                jobs.add(() -> {
                    for (int i = 0; i < perThread; i++) {
                        boins.write(data, BlobMetadata.EMPTY);
                    }
                    return null;
                });
            }
            for (Future<Void> f : pool.invokeAll(jobs)) {
                f.get();
            }
        }
        report(name, (long) blobSize * perThread * threads, perThread * threads, System.nanoTime() - start);
    }

    /** Delete + rewrite of same-size blobs must reuse cells (no file growth). */
    private void benchReuse(Boins boins) throws Exception {
        int blobSize = 256 * KIB;
        int count = 500;
        byte[] data = new byte[blobSize];
        new Random(3L).nextBytes(data);
        long[] ids = new long[count];
        for (int i = 0; i < count; i++) {
            ids[i] = boins.write(data, BlobMetadata.EMPTY).blobId();
        }
        for (long id : ids) {
            boins.delete(id);
        }
        long start = System.nanoTime();
        for (int i = 0; i < count; i++) {
            boins.write(data, BlobMetadata.EMPTY);
        }
        report("write 256 KiB into reused cells ", (long) blobSize * count, count, System.nanoTime() - start);
    }

    private static void report(String name, long bytes, int ops, long nanos) {
        double seconds = nanos / 1_000_000_000.0;
        double mbPerSec = bytes / (double) MIB / seconds;
        double opsPerSec = ops / seconds;
        System.out.printf("%s | %8.1f MiB/s | %9.0f ops/s | %d ops in %.2fs%n",
                name, mbPerSec, opsPerSec, ops, seconds);
    }

    private static String osSummary() {
        return System.getProperty("os.name") + ", Java " + System.getProperty("java.version")
                + ", " + Runtime.getRuntime().availableProcessors() + " cpus";
    }
}
