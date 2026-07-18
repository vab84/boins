package io.boins.server;

import io.boins.core.BoinsOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;

/**
 * End-to-end S3-over-HTTP throughput benchmark using the AWS SDK as the client. Opt-in:
 *
 * <pre>./gradlew :boins-server:test --tests "io.boins.server.HttpBenchmark" -Dboins.bench=true</pre>
 */
@EnabledIfSystemProperty(named = "boins.bench", matches = "true")
class HttpBenchmark {

    private static final int KIB = 1024;
    private static final int MIB = 1024 * 1024;
    private static final String BUCKET = "bench-bucket";

    @TempDir
    Path tempDir;

    @Test
    void throughput() throws Exception {
        BoinsServerConfig config = new BoinsServerConfig();
        config.host = "127.0.0.1";
        config.port = 0;
        config.bucketDataDir = tempDir.resolve("buckets").toString();
        config.faults.dir = tempDir.resolve("faults").toString();
        BoinsServerConfig.Storage.Repo repo = new BoinsServerConfig.Storage.Repo();
        repo.dir = tempDir.resolve("repo").toString();
        config.storage.repositories.add(repo);
        config.storage.blobFileLimit = 30;
        config.storage.minRepositoryFreeBytes = 0L;
        config.storage.fsyncMode = BoinsOptions.FsyncMode.INTERVAL;
        BoinsServerConfig.BucketDef bucket = new BoinsServerConfig.BucketDef();
        bucket.name = BUCKET;
        bucket.accessKey = "bench-access";
        bucket.secretKey = "bench-secret-0123456789";
        config.buckets.add(bucket);

        Supplier<S3Client> clientFactory = () -> S3Client.builder()
                .endpointOverride(URI.create("http://127.0.0.1:" + serverPort))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("bench-access", "bench-secret-0123456789")))
                .forcePathStyle(true)
                .httpClient(UrlConnectionHttpClient.create())
                .build();

        try (BoinsServer server = BoinsServer.start(config)) {
            serverPort = server.port();
            System.out.println();
            System.out.println("=== Boins S3-over-HTTP benchmark (AWS SDK v2 client, loopback) ===");
            try (S3Client s3 = clientFactory.get()) {
                benchPut(s3, "PUT  64 KiB objects, 1 thread ", 64 * KIB, 500);
                benchPut(s3, "PUT   1 MiB objects, 1 thread ", MIB, 200);
                benchGet(s3, "GET   1 MiB objects, 1 thread ", MIB, 200);
            }
            benchConcurrentPut(clientFactory, "PUT  64 KiB objects, 8 threads", 64 * KIB, 1_600);
            benchConcurrentGet(clientFactory, "GET  64 KiB objects, 8 threads", 64 * KIB, 1_600);
            System.out.println("=== end of benchmark ===");
        }
    }

    private volatile int serverPort;

    private void benchPut(S3Client s3, String name, int size, int count) {
        byte[] data = new byte[size];
        new Random(size).nextBytes(data);
        long start = System.nanoTime();
        for (int i = 0; i < count; i++) {
            String key = "put/" + size + "/" + i;
            s3.putObject(b -> b.bucket(BUCKET).key(key), RequestBody.fromBytes(data));
        }
        report(name, (long) size * count, count, System.nanoTime() - start);
    }

    private void benchGet(S3Client s3, String name, int size, int count) throws Exception {
        byte[] buffer = new byte[64 * KIB];
        long start = System.nanoTime();
        for (int i = 0; i < count; i++) {
            String key = "put/" + size + "/" + (i % count);
            try (InputStream in = s3.getObject(b -> b.bucket(BUCKET).key(key))) {
                while (in.read(buffer) >= 0) {
                    // drain
                }
            }
        }
        report(name, (long) size * count, count, System.nanoTime() - start);
    }

    private void benchConcurrentPut(Supplier<S3Client> factory, String name, int size, int totalCount)
            throws Exception {
        int threads = 8;
        int perThread = totalCount / threads;
        byte[] data = new byte[size];
        new Random(11L).nextBytes(data);
        long start = System.nanoTime();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Callable<Void>> jobs = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int thread = t;
                jobs.add(() -> {
                    try (S3Client s3 = factory.get()) {
                        for (int i = 0; i < perThread; i++) {
                            String key = "mt/" + thread + "/" + i;
                            s3.putObject(b -> b.bucket(BUCKET).key(key), RequestBody.fromBytes(data));
                        }
                    }
                    return null;
                });
            }
            for (Future<Void> f : pool.invokeAll(jobs)) {
                f.get();
            }
        }
        report(name, (long) size * perThread * threads, perThread * threads, System.nanoTime() - start);
    }

    private void benchConcurrentGet(Supplier<S3Client> factory, String name, int size, int totalCount)
            throws Exception {
        int threads = 8;
        int perThread = totalCount / threads;
        long start = System.nanoTime();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Callable<Void>> jobs = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int thread = t;
                jobs.add(() -> {
                    byte[] buffer = new byte[64 * KIB];
                    try (S3Client s3 = factory.get()) {
                        for (int i = 0; i < perThread; i++) {
                            String key = "mt/" + thread + "/" + i;
                            try (InputStream in = s3.getObject(b -> b.bucket(BUCKET).key(key))) {
                                while (in.read(buffer) >= 0) {
                                    // drain
                                }
                            }
                        }
                    }
                    return null;
                });
            }
            for (Future<Void> f : pool.invokeAll(jobs)) {
                f.get();
            }
        }
        report(name, (long) size * perThread * threads, perThread * threads, System.nanoTime() - start);
    }

    private static void report(String name, long bytes, int ops, long nanos) {
        double seconds = nanos / 1_000_000_000.0;
        double mbPerSec = bytes / (double) MIB / seconds;
        double opsPerSec = ops / seconds;
        System.out.printf("%s | %8.1f MiB/s | %9.0f ops/s | %d ops in %.2fs%n",
                name, mbPerSec, opsPerSec, ops, seconds);
    }
}
