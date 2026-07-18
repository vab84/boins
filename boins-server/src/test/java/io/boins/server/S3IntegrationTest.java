package io.boins.server;

import io.boins.core.BoinsOptions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end compatibility tests: the official AWS SDK v2 talks to a live Boins server.
 * The SDK exercises SigV4 header auth, aws-chunked payloads with trailing checksums,
 * multipart uploads and presigned URLs exactly like production S3 clients do.
 */
class S3IntegrationTest {

    private static final String BUCKET = "test-bucket";
    private static final String ACCESS_KEY = "boins-test-access";
    private static final String SECRET_KEY = "boins-test-secret-key-0123456789";
    private static final String ADMIN_KEY = "test-admin-key";

    @TempDir
    static Path tempDir;

    private static BoinsServer server;
    private static S3Client s3;
    private static S3Presigner presigner;
    private static String endpoint;
    private static final HttpClient http = HttpClient.newHttpClient();

    @BeforeAll
    static void startServer() throws Exception {
        BoinsServerConfig config = new BoinsServerConfig();
        config.host = "127.0.0.1";
        config.port = 0;
        config.adminKey = ADMIN_KEY;
        config.bucketDataDir = tempDir.resolve("buckets").toString();
        config.faults.dir = tempDir.resolve("faults").toString();
        BoinsServerConfig.Storage.Repo repo = new BoinsServerConfig.Storage.Repo();
        repo.dir = tempDir.resolve("repo1").toString();
        config.storage.repositories.add(repo);
        config.storage.blobFileLimit = 24; // 16 MiB blob files for tests
        config.storage.minRepositoryFreeBytes = 0L;
        config.storage.fsyncMode = BoinsOptions.FsyncMode.NEVER;
        BoinsServerConfig.BucketDef bucket = new BoinsServerConfig.BucketDef();
        bucket.name = BUCKET;
        bucket.accessKey = ACCESS_KEY;
        bucket.secretKey = SECRET_KEY;
        config.buckets.add(bucket);

        server = BoinsServer.start(config);
        endpoint = "http://127.0.0.1:" + server.port();

        StaticCredentialsProvider credentials = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY));
        s3 = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.US_EAST_1)
                .credentialsProvider(credentials)
                .forcePathStyle(true)
                .httpClient(UrlConnectionHttpClient.create())
                .build();
        presigner = S3Presigner.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.US_EAST_1)
                .credentialsProvider(credentials)
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
    }

    @AfterAll
    static void stopServer() throws Exception {
        if (s3 != null) {
            s3.close();
        }
        if (presigner != null) {
            presigner.close();
        }
        if (server != null) {
            server.close();
        }
    }

    private static byte[] randomBytes(int size, long seed) {
        byte[] bytes = new byte[size];
        new Random(seed).nextBytes(bytes);
        return bytes;
    }

    private static String md5Hex(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(data));
    }

    // ---------------------------------------------------------------- basic object lifecycle

    @Test
    void putAndGetRoundTrip() throws Exception {
        byte[] data = randomBytes(100_000, 1L);
        var putResponse = s3.putObject(b -> b.bucket(BUCKET).key("docs/roundtrip.bin")
                        .contentType("application/x-boins-test")
                        .metadata(Map.of("author", "alice", "purpose", "integration test")),
                RequestBody.fromBytes(data));
        assertEquals("\"" + md5Hex(data) + "\"", putResponse.eTag());

        try (ResponseInputStream<GetObjectResponse> in =
                     s3.getObject(b -> b.bucket(BUCKET).key("docs/roundtrip.bin"))) {
            assertArrayEquals(data, in.readAllBytes());
            assertEquals("application/x-boins-test", in.response().contentType());
            assertEquals("alice", in.response().metadata().get("author"));
            assertEquals("integration test", in.response().metadata().get("purpose"));
            assertEquals(data.length, in.response().contentLength());
        }
    }

    @Test
    void headObjectReportsAttributes() throws Exception {
        byte[] data = randomBytes(5_000, 2L);
        s3.putObject(b -> b.bucket(BUCKET).key("head/probe.bin").contentType("image/png"),
                RequestBody.fromBytes(data));
        HeadObjectResponse head = s3.headObject(b -> b.bucket(BUCKET).key("head/probe.bin"));
        assertEquals(5_000L, head.contentLength());
        assertEquals("image/png", head.contentType());
        assertEquals("\"" + md5Hex(data) + "\"", head.eTag());
        assertNotNull(head.lastModified());
    }

    @Test
    void overwriteReplacesContent() throws Exception {
        s3.putObject(b -> b.bucket(BUCKET).key("overwrite.bin"), RequestBody.fromBytes(randomBytes(1_000, 3L)));
        byte[] second = randomBytes(2_000, 4L);
        s3.putObject(b -> b.bucket(BUCKET).key("overwrite.bin"), RequestBody.fromBytes(second));
        try (var in = s3.getObject(b -> b.bucket(BUCKET).key("overwrite.bin"))) {
            assertArrayEquals(second, in.readAllBytes());
        }
    }

    @Test
    void rangeRequests() throws Exception {
        byte[] data = randomBytes(10_000, 5L);
        s3.putObject(b -> b.bucket(BUCKET).key("range.bin"), RequestBody.fromBytes(data));
        try (ResponseInputStream<GetObjectResponse> in =
                     s3.getObject(b -> b.bucket(BUCKET).key("range.bin").range("bytes=100-199"))) {
            byte[] slice = in.readAllBytes();
            assertEquals(100, slice.length);
            assertArrayEquals(java.util.Arrays.copyOfRange(data, 100, 200), slice);
            assertEquals("bytes 100-199/10000", in.response().contentRange());
        }
        // suffix range: last 50 bytes
        try (ResponseInputStream<GetObjectResponse> in =
                     s3.getObject(b -> b.bucket(BUCKET).key("range.bin").range("bytes=-50"))) {
            assertArrayEquals(java.util.Arrays.copyOfRange(data, 9_950, 10_000), in.readAllBytes());
        }
    }

    @Test
    void deleteObjectIsIdempotent() {
        s3.putObject(b -> b.bucket(BUCKET).key("kill/me.bin"), RequestBody.fromBytes(randomBytes(100, 6L)));
        s3.deleteObject(b -> b.bucket(BUCKET).key("kill/me.bin"));
        assertThrows(NoSuchKeyException.class,
                () -> s3.getObject(b -> b.bucket(BUCKET).key("kill/me.bin")).close());
        // deleting a missing key is still 204
        s3.deleteObject(b -> b.bucket(BUCKET).key("kill/me.bin"));
    }

    @Test
    void deleteObjectsBatch() {
        for (int i = 0; i < 3; i++) {
            int n = i;
            s3.putObject(b -> b.bucket(BUCKET).key("batch/obj-" + n), RequestBody.fromBytes(randomBytes(64, n)));
        }
        var response = s3.deleteObjects(b -> b.bucket(BUCKET).delete(d -> d.objects(
                ObjectIdentifier.builder().key("batch/obj-0").build(),
                ObjectIdentifier.builder().key("batch/obj-1").build(),
                ObjectIdentifier.builder().key("batch/obj-2").build())));
        assertEquals(3, response.deleted().size());
        assertTrue(response.errors().isEmpty());
        assertThrows(NoSuchKeyException.class,
                () -> s3.headObject(b -> b.bucket(BUCKET).key("batch/obj-1")));
    }

    @Test
    void copyObject() throws Exception {
        byte[] data = randomBytes(3_333, 7L);
        s3.putObject(b -> b.bucket(BUCKET).key("copy/source.bin").contentType("text/csv"),
                RequestBody.fromBytes(data));
        var copy = s3.copyObject(b -> b.sourceBucket(BUCKET).sourceKey("copy/source.bin")
                .destinationBucket(BUCKET).destinationKey("copy/target.bin"));
        assertEquals("\"" + md5Hex(data) + "\"", copy.copyObjectResult().eTag());
        try (var in = s3.getObject(b -> b.bucket(BUCKET).key("copy/target.bin"))) {
            assertArrayEquals(data, in.readAllBytes());
            assertEquals("text/csv", in.response().contentType());
        }
    }

    @Test
    void emptyObject() throws Exception {
        s3.putObject(b -> b.bucket(BUCKET).key("empty.bin"), RequestBody.empty());
        try (var in = s3.getObject(b -> b.bucket(BUCKET).key("empty.bin"))) {
            assertEquals(0, in.readAllBytes().length);
            assertEquals("\"d41d8cd98f00b204e9800998ecf8427e\"", in.response().eTag());
        }
    }

    @Test
    void conditionalGet() {
        s3.putObject(b -> b.bucket(BUCKET).key("cond.bin"), RequestBody.fromBytes(randomBytes(10, 8L)));
        String etag = s3.headObject(b -> b.bucket(BUCKET).key("cond.bin")).eTag();
        S3Exception notModified = assertThrows(S3Exception.class,
                () -> s3.getObject(b -> b.bucket(BUCKET).key("cond.bin").ifNoneMatch(etag)).close());
        assertEquals(304, notModified.statusCode());
        S3Exception preconditionFailed = assertThrows(S3Exception.class,
                () -> s3.getObject(b -> b.bucket(BUCKET).key("cond.bin").ifMatch("\"deadbeef\"")).close());
        assertEquals(412, preconditionFailed.statusCode());
    }

    // ---------------------------------------------------------------- listing

    @Test
    void listObjectsV2WithPrefixDelimiterAndPagination() {
        for (int i = 0; i < 12; i++) {
            int n = i;
            s3.putObject(b -> b.bucket(BUCKET).key(String.format("list/flat/%03d.bin", n)),
                    RequestBody.fromBytes(new byte[]{1}));
        }
        s3.putObject(b -> b.bucket(BUCKET).key("list/deep/a/x.bin"), RequestBody.fromBytes(new byte[]{1}));
        s3.putObject(b -> b.bucket(BUCKET).key("list/deep/b/y.bin"), RequestBody.fromBytes(new byte[]{1}));

        // prefix + pagination
        List<String> keys = new ArrayList<>();
        String token = null;
        int pages = 0;
        do {
            String continuation = token;
            ListObjectsV2Response page = s3.listObjectsV2(b -> {
                b.bucket(BUCKET).prefix("list/flat/").maxKeys(5);
                if (continuation != null) {
                    b.continuationToken(continuation);
                }
            });
            page.contents().forEach(o -> keys.add(o.key()));
            token = page.nextContinuationToken();
            pages++;
        } while (token != null);
        assertEquals(12, keys.size());
        assertEquals(3, pages);
        assertEquals("list/flat/000.bin", keys.getFirst());
        assertEquals("list/flat/011.bin", keys.getLast());

        // delimiter grouping
        ListObjectsV2Response grouped = s3.listObjectsV2(b -> b.bucket(BUCKET).prefix("list/deep/").delimiter("/"));
        assertEquals(0, grouped.contents().size());
        assertEquals(List.of("list/deep/a/", "list/deep/b/"),
                grouped.commonPrefixes().stream().map(p -> p.prefix()).toList());
    }

    @Test
    void listBuckets() {
        var response = s3.listBuckets();
        assertEquals(1, response.buckets().size());
        assertEquals(BUCKET, response.buckets().getFirst().name());
    }

    @Test
    void headAndLocationOfBucket() {
        s3.headBucket(b -> b.bucket(BUCKET));
        var location = s3.getBucketLocation(b -> b.bucket(BUCKET));
        assertNotNull(location);
    }

    // ---------------------------------------------------------------- multipart

    @Test
    void multipartUpload() throws Exception {
        byte[] part1 = randomBytes(300_000, 10L);
        byte[] part2 = randomBytes(200_000, 11L);
        CreateMultipartUploadResponse create = s3.createMultipartUpload(
                b -> b.bucket(BUCKET).key("multi/big.bin").contentType("application/zip"));
        String uploadId = create.uploadId();
        UploadPartResponse up1 = s3.uploadPart(
                b -> b.bucket(BUCKET).key("multi/big.bin").uploadId(uploadId).partNumber(1),
                RequestBody.fromBytes(part1));
        UploadPartResponse up2 = s3.uploadPart(
                b -> b.bucket(BUCKET).key("multi/big.bin").uploadId(uploadId).partNumber(2),
                RequestBody.fromBytes(part2));

        var complete = s3.completeMultipartUpload(b -> b.bucket(BUCKET).key("multi/big.bin")
                .uploadId(uploadId)
                .multipartUpload(CompletedMultipartUpload.builder().parts(
                        CompletedPart.builder().partNumber(1).eTag(up1.eTag()).build(),
                        CompletedPart.builder().partNumber(2).eTag(up2.eTag()).build()).build()));
        assertTrue(complete.eTag().endsWith("-2\""), "multipart etag must end with part count: " + complete.eTag());

        byte[] all = new byte[part1.length + part2.length];
        System.arraycopy(part1, 0, all, 0, part1.length);
        System.arraycopy(part2, 0, all, part1.length, part2.length);
        try (var in = s3.getObject(b -> b.bucket(BUCKET).key("multi/big.bin"))) {
            assertArrayEquals(all, in.readAllBytes());
            assertEquals("application/zip", in.response().contentType());
        }
    }

    @Test
    void abortedMultipartUploadDisappears() {
        CreateMultipartUploadResponse create = s3.createMultipartUpload(
                b -> b.bucket(BUCKET).key("multi/aborted.bin"));
        s3.uploadPart(b -> b.bucket(BUCKET).key("multi/aborted.bin")
                        .uploadId(create.uploadId()).partNumber(1),
                RequestBody.fromBytes(randomBytes(1_000, 12L)));
        s3.abortMultipartUpload(b -> b.bucket(BUCKET).key("multi/aborted.bin").uploadId(create.uploadId()));
        S3Exception e = assertThrows(S3Exception.class,
                () -> s3.completeMultipartUpload(b -> b.bucket(BUCKET).key("multi/aborted.bin")
                        .uploadId(create.uploadId())
                        .multipartUpload(CompletedMultipartUpload.builder()
                                .parts(CompletedPart.builder().partNumber(1).eTag("\"0\"").build()).build())));
        assertEquals(404, e.statusCode());
    }

    // ---------------------------------------------------------------- presigned (direct-to-storage)

    @Test
    void presignedDownload() throws Exception {
        byte[] data = randomBytes(9_000, 13L);
        s3.putObject(b -> b.bucket(BUCKET).key("presign/download.bin"), RequestBody.fromBytes(data));
        PresignedGetObjectRequest presigned = presigner.presignGetObject(b -> b
                .signatureDuration(Duration.ofMinutes(5))
                .getObjectRequest(g -> g.bucket(BUCKET).key("presign/download.bin")
                        .responseContentDisposition("attachment; filename=\"annual-report.bin\"")
                        .responseContentType("application/x-download")));
        HttpResponse<byte[]> response = http.send(
                HttpRequest.newBuilder(presigned.url().toURI()).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, response.statusCode());
        assertArrayEquals(data, response.body());
        // The signed response-* overrides must be honoured (custom download filename).
        assertEquals("attachment; filename=\"annual-report.bin\"",
                response.headers().firstValue("Content-Disposition").orElse(null));
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("application/x-download"));
    }

    @Test
    void presignedUpload() throws Exception {
        byte[] data = randomBytes(7_777, 14L);
        PresignedPutObjectRequest presigned = presigner.presignPutObject(b -> b
                .signatureDuration(Duration.ofMinutes(5))
                .putObjectRequest(p -> p.bucket(BUCKET).key("presign/upload.bin")));
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(presigned.url().toURI())
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(data)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        try (var in = s3.getObject(b -> b.bucket(BUCKET).key("presign/upload.bin"))) {
            assertArrayEquals(data, in.readAllBytes());
        }
    }

    @Test
    void tamperedPresignedUrlIsRejected() throws Exception {
        s3.putObject(b -> b.bucket(BUCKET).key("presign/secret.bin"), RequestBody.fromBytes(randomBytes(10, 15L)));
        PresignedGetObjectRequest presigned = presigner.presignGetObject(b -> b
                .signatureDuration(Duration.ofMinutes(5))
                .getObjectRequest(g -> g.bucket(BUCKET).key("presign/secret.bin")));
        // Redirect the signed URL to another key: the signature must not survive.
        String tampered = presigned.url().toString().replace("secret.bin", "other.bin");
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create(tampered)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(403, response.statusCode());
        assertTrue(response.body().contains("SignatureDoesNotMatch"));
    }

    // ---------------------------------------------------------------- auth failures

    @Test
    void wrongSecretKeyIsRejected() {
        try (S3Client bad = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(ACCESS_KEY, "wrong-secret")))
                .forcePathStyle(true)
                .httpClient(UrlConnectionHttpClient.create())
                .build()) {
            S3Exception e = assertThrows(S3Exception.class,
                    () -> bad.headObject(b -> b.bucket(BUCKET).key("any")));
            assertEquals(403, e.statusCode());
        }
    }

    @Test
    void unknownAccessKeyIsRejected() {
        try (S3Client bad = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("who-is-this", "whatever")))
                .forcePathStyle(true)
                .httpClient(UrlConnectionHttpClient.create())
                .build()) {
            S3Exception e = assertThrows(S3Exception.class, () -> bad.listBuckets());
            assertEquals(403, e.statusCode());
        }
    }

    @Test
    void unknownBucketIs404() {
        S3Exception e = assertThrows(S3Exception.class,
                () -> s3.headBucket(b -> b.bucket("no-such-bucket")));
        assertEquals(404, e.statusCode());
    }

    @Test
    void unsignedRequestIsRejected() throws Exception {
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create(endpoint + "/" + BUCKET + "/anything")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(403, response.statusCode());
    }

    // ---------------------------------------------------------------- admin interface

    @Test
    void adminEndpointsRequireKey() throws Exception {
        HttpResponse<String> unauthorized = http.send(
                HttpRequest.newBuilder(URI.create(endpoint + "/_admin/metrics")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(401, unauthorized.statusCode());

        HttpResponse<String> metrics = http.send(
                HttpRequest.newBuilder(URI.create(endpoint + "/_admin/metrics"))
                        .header("X-Boins-Admin-Key", ADMIN_KEY).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, metrics.statusCode());
        assertTrue(metrics.body().contains("bytesWritten"));
        assertTrue(metrics.body().contains("totalRequests"));

        HttpResponse<String> state = http.send(
                HttpRequest.newBuilder(URI.create(endpoint + "/_admin/state"))
                        .header("Authorization", "Bearer " + ADMIN_KEY).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, state.statusCode());
        assertTrue(state.body().contains("repositories"));
        assertTrue(state.body().contains(BUCKET));

        HttpResponse<String> health = http.send(
                HttpRequest.newBuilder(URI.create(endpoint + "/_admin/health")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, health.statusCode());
        assertEquals("ok", health.body());
    }

    @Test
    void faultCallbacksReceiveServerErrors() throws Exception {
        List<io.boins.core.faults.FaultEvent> events = new java.util.concurrent.CopyOnWriteArrayList<>();
        server.onFault(events::add);
        // A tampered chunked upload triggers a server-side write failure path:
        // simplest reliable fault source is an admin fault listing of a recorded exception.
        server.faults().record(new IOException("synthetic fault for callback test"),
                Map.of("origin", "integration-test"));
        assertFalse(events.isEmpty());
        assertEquals("synthetic fault for callback test", events.getFirst().throwable().getMessage());

        HttpResponse<String> faults = http.send(
                HttpRequest.newBuilder(URI.create(endpoint + "/_admin/faults"))
                        .header("X-Boins-Admin-Key", ADMIN_KEY).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, faults.statusCode());
        assertTrue(faults.body().contains("synthetic fault for callback test"));
    }
}
