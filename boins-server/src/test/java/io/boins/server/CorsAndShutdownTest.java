package io.boins.server;

import io.boins.core.BoinsOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CorsAndShutdownTest {

    @TempDir
    Path tempDir;

    private BoinsServerConfig config(String... corsOrigins) {
        BoinsServerConfig config = new BoinsServerConfig();
        config.host = "127.0.0.1";
        config.port = 0;
        config.bucketDataDir = tempDir.resolve("buckets").toString();
        config.faults.dir = tempDir.resolve("faults").toString();
        BoinsServerConfig.Storage.Repo repo = new BoinsServerConfig.Storage.Repo();
        repo.dir = tempDir.resolve("repo").toString();
        config.storage.repositories.add(repo);
        config.storage.blobFileLimit = 20;
        config.storage.minRepositoryFreeBytes = 0L;
        config.storage.fsyncMode = BoinsOptions.FsyncMode.NEVER;
        BoinsServerConfig.BucketDef bucket = new BoinsServerConfig.BucketDef();
        bucket.name = "cors-bucket";
        bucket.accessKey = "cors-access";
        bucket.secretKey = "cors-secret-0123456789";
        config.buckets.add(bucket);
        config.cors.allowedOrigins.addAll(java.util.List.of(corsOrigins));
        return config;
    }

    @Test
    void preflightAndCorsHeaders() throws Exception {
        try (BoinsServer server = BoinsServer.start(config("https://app.example.com"));
             HttpClient http = HttpClient.newHttpClient()) {
            String endpoint = "http://127.0.0.1:" + server.port();

            // Preflight: no SigV4 auth, must succeed for the allowed origin.
            HttpResponse<String> preflight = http.send(HttpRequest.newBuilder(
                            URI.create(endpoint + "/cors-bucket/uploads/avatar.png"))
                    .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                    .header("Origin", "https://app.example.com")
                    .header("Access-Control-Request-Method", "PUT")
                    .header("Access-Control-Request-Headers", "content-type")
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(204, preflight.statusCode());
            assertEquals("https://app.example.com",
                    preflight.headers().firstValue("Access-Control-Allow-Origin").orElse(null));
            assertTrue(preflight.headers().firstValue("Access-Control-Allow-Methods").orElse("").contains("PUT"));
            assertEquals("content-type",
                    preflight.headers().firstValue("Access-Control-Allow-Headers").orElse(null));

            // A disallowed origin gets no CORS headers.
            HttpResponse<String> denied = http.send(HttpRequest.newBuilder(
                            URI.create(endpoint + "/cors-bucket/x"))
                    .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                    .header("Origin", "https://evil.example.com")
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertNull(denied.headers().firstValue("Access-Control-Allow-Origin").orElse(null));

            // Regular (non-preflight) responses also carry the CORS headers.
            HttpResponse<String> get = http.send(HttpRequest.newBuilder(
                            URI.create(endpoint + "/_admin/health"))
                    .header("Origin", "https://app.example.com")
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals("https://app.example.com",
                    get.headers().firstValue("Access-Control-Allow-Origin").orElse(null));
            assertNotNull(get.headers().firstValue("Access-Control-Expose-Headers").orElse(null));
        }
    }

    @Test
    void wildcardOrigin() throws Exception {
        try (BoinsServer server = BoinsServer.start(config("*"));
             HttpClient http = HttpClient.newHttpClient()) {
            HttpResponse<String> preflight = http.send(HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + server.port() + "/anything/goes"))
                    .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                    .header("Origin", "https://whatever.example.com")
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(204, preflight.statusCode());
            assertEquals("*", preflight.headers().firstValue("Access-Control-Allow-Origin").orElse(null));
        }
    }

    @Test
    void corsDisabledByDefault() throws Exception {
        try (BoinsServer server = BoinsServer.start(config());
             HttpClient http = HttpClient.newHttpClient()) {
            HttpResponse<String> get = http.send(HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + server.port() + "/_admin/health"))
                    .header("Origin", "https://app.example.com")
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertNull(get.headers().firstValue("Access-Control-Allow-Origin").orElse(null));
        }
    }

    @Test
    void closeIsGracefulAndIdempotent() throws Exception {
        BoinsServer server = BoinsServer.start(config());
        long start = System.currentTimeMillis();
        server.close();
        long elapsed = System.currentTimeMillis() - start;
        // With no in-flight requests the drain loop must not eat the full graceful budget.
        assertTrue(elapsed < config().gracefulShutdownMillis / 2,
                "close with no traffic should be quick, took " + elapsed + " ms");
        server.close(); // second close is a no-op
    }
}
