package io.boins.server.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.boins.core.Boins;
import io.boins.core.BoinsException;
import io.boins.core.faults.FaultStore;
import io.boins.server.HttpMetrics;
import io.boins.server.bucket.Bucket;
import io.boins.server.bucket.BucketRegistry;
import io.javalin.http.Context;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The admin interface: storage state, metrics and recorded faults as JSON under
 * {@code /_admin/*}. Bucket names cannot start with an underscore, so the prefix can
 * never collide with S3 routes.
 *
 * <p>Authentication: {@code Authorization: Bearer <adminKey>} or the
 * {@code X-Boins-Admin-Key} header. Disabled entirely when no admin key is configured.</p>
 */
public final class AdminHandler {

    private final Boins core;
    private final BucketRegistry buckets;
    private final FaultStore faults;
    private final HttpMetrics httpMetrics;
    private final String adminKey;
    private final ObjectMapper json = new ObjectMapper();
    private final long startedMillis = System.currentTimeMillis();

    public AdminHandler(Boins core, BucketRegistry buckets, FaultStore faults,
                        HttpMetrics httpMetrics, String adminKey) {
        this.core = core;
        this.buckets = buckets;
        this.faults = faults;
        this.httpMetrics = httpMetrics;
        this.adminKey = adminKey;
    }

    /** {@code GET /_admin/state} — repositories, free cells, buckets. */
    public void state(Context ctx) throws BoinsException, IOException {
        authorize(ctx);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("startedAt", startedMillis);
        body.put("uptimeMillis", System.currentTimeMillis() - startedMillis);
        body.put("storage", core.state());
        Map<String, Object> bucketStates = new LinkedHashMap<>();
        for (Bucket bucket : buckets.all()) {
            bucketStates.put(bucket.name(), Map.of("objectCount", bucket.keys().size()));
        }
        body.put("buckets", bucketStates);
        sendJson(ctx, body);
    }

    /** {@code GET /_admin/metrics} — storage metrics + HTTP counters. */
    public void metrics(Context ctx) throws IOException {
        authorize(ctx);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("storage", core.metrics());
        body.put("http", httpMetrics.snapshot());
        sendJson(ctx, body);
    }

    /** {@code GET /_admin/faults} — deduplicated fault summaries, most recent first. */
    public void faults(Context ctx) throws IOException {
        authorize(ctx);
        sendJson(ctx, faults.list());
    }

    /** {@code GET /_admin/faults/{hash}} — full report text of one fault. */
    public void faultReport(Context ctx) throws IOException {
        authorize(ctx);
        String report = faults.reportText(ctx.pathParam("hash"));
        if (report == null) {
            ctx.status(404).result("No fault with hash " + ctx.pathParam("hash"));
            return;
        }
        ctx.contentType("text/plain; charset=utf-8");
        ctx.result(report);
    }

    /** {@code POST /_admin/flush} — force all dirty files to disk. */
    public void flush(Context ctx) throws BoinsException {
        authorize(ctx);
        core.flush();
        ctx.status(200).result("flushed");
    }

    /** {@code GET /_admin/health} — liveness probe (no auth: safe, static answer). */
    public void health(Context ctx) {
        ctx.result("ok");
    }

    private void authorize(Context ctx) {
        if (adminKey == null || adminKey.isBlank()) {
            ctx.status(404);
            throw new AdminDisabled();
        }
        String provided = ctx.header("X-Boins-Admin-Key");
        if (provided == null) {
            String authorization = ctx.header("Authorization");
            if (authorization != null && authorization.startsWith("Bearer ")) {
                provided = authorization.substring("Bearer ".length()).trim();
            }
        }
        if (provided == null || !MessageDigest.isEqual(
                provided.getBytes(StandardCharsets.UTF_8), adminKey.getBytes(StandardCharsets.UTF_8))) {
            throw new Unauthorized();
        }
    }

    private void sendJson(Context ctx, Object body) throws IOException {
        ctx.contentType("application/json");
        ctx.result(json.writerWithDefaultPrettyPrinter().writeValueAsBytes(body));
    }

    /** Admin endpoints respond 404 when no admin key is configured. */
    public static final class AdminDisabled extends RuntimeException {
        AdminDisabled() {
            super(null, null, false, false);
        }
    }

    /** Wrong or missing admin credentials. */
    public static final class Unauthorized extends RuntimeException {
        Unauthorized() {
            super(null, null, false, false);
        }
    }
}
