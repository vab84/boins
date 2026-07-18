package io.boins.server;

import io.boins.core.BoinsOptions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.SdkHttpConfigurationOption;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.utils.AttributeMap;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.Objects;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies the SSL server option end to end: the AWS SDK and a plain HTTPS client talk to
 * Boins over TLS using a PEM certificate/key pair, and plaintext HTTP on the TLS port fails.
 */
class SslIntegrationTest {

    private static final String BUCKET = "ssl-bucket";
    private static final String ACCESS_KEY = "ssl-access";
    private static final String SECRET_KEY = "ssl-secret-0123456789";

    @TempDir
    static Path tempDir;

    private static BoinsServer server;
    private static S3Client s3;
    private static String endpoint;

    @BeforeAll
    static void startServer() throws Exception {
        Path cert = copyResource("/tls/cert.pem", tempDir.resolve("cert.pem"));
        Path key = copyResource("/tls/key.pem", tempDir.resolve("key.pem"));

        BoinsServerConfig config = new BoinsServerConfig();
        config.host = "127.0.0.1";
        config.port = 0;
        config.ssl.enabled = true;
        config.ssl.certificatePath = cert.toString();
        config.ssl.privateKeyPath = key.toString();
        config.bucketDataDir = tempDir.resolve("buckets").toString();
        config.faults.dir = tempDir.resolve("faults").toString();
        BoinsServerConfig.Storage.Repo repo = new BoinsServerConfig.Storage.Repo();
        repo.dir = tempDir.resolve("repo1").toString();
        config.storage.repositories.add(repo);
        config.storage.blobFileLimit = 24;
        config.storage.minRepositoryFreeBytes = 0L;
        config.storage.fsyncMode = BoinsOptions.FsyncMode.NEVER;
        BoinsServerConfig.BucketDef bucket = new BoinsServerConfig.BucketDef();
        bucket.name = BUCKET;
        bucket.accessKey = ACCESS_KEY;
        bucket.secretKey = SECRET_KEY;
        config.buckets.add(bucket);

        server = BoinsServer.start(config);
        endpoint = "https://127.0.0.1:" + server.port();

        s3 = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
                .forcePathStyle(true)
                .httpClient(UrlConnectionHttpClient.builder().buildWithDefaults(AttributeMap.builder()
                        .put(SdkHttpConfigurationOption.TRUST_ALL_CERTIFICATES, Boolean.TRUE)
                        .build()))
                .build();
    }

    @AfterAll
    static void stopServer() throws Exception {
        if (s3 != null) {
            s3.close();
        }
        if (server != null) {
            server.close();
        }
    }

    private static Path copyResource(String resource, Path target) throws IOException {
        try (InputStream in = Objects.requireNonNull(
                SslIntegrationTest.class.getResourceAsStream(resource), resource)) {
            Files.copy(in, target);
        }
        return target;
    }

    @Test
    void putAndGetOverTls() throws Exception {
        byte[] data = new byte[50_000];
        new Random(42L).nextBytes(data);
        s3.putObject(b -> b.bucket(BUCKET).key("tls/object.bin"), RequestBody.fromBytes(data));
        try (var in = s3.getObject(b -> b.bucket(BUCKET).key("tls/object.bin"))) {
            assertArrayEquals(data, in.readAllBytes());
        }
    }

    @Test
    void healthEndpointOverTls() throws Exception {
        SSLContext trustAll = SSLContext.getInstance("TLS");
        trustAll.init(null, new TrustManager[]{new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        }}, null);
        try (HttpClient client = HttpClient.newBuilder().sslContext(trustAll).build()) {
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create(endpoint + "/_admin/health")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertEquals("ok", response.body());
        }
    }

    @Test
    void plaintextHttpOnTlsPortFails() {
        try (HttpClient client = HttpClient.newHttpClient()) {
            assertThrows(IOException.class, () -> client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/_admin/health"))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString()));
        }
    }
}
