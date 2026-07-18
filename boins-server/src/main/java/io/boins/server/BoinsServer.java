package io.boins.server;

import io.boins.core.Boins;
import io.boins.core.BoinsException;
import io.boins.core.faults.FaultEvent;
import io.boins.core.faults.FaultStore;
import io.boins.server.admin.AdminHandler;
import io.boins.server.bucket.Bucket;
import io.boins.server.bucket.BucketRegistry;
import io.boins.server.bucket.MultipartUploads;
import io.boins.server.s3.S3Exception;
import io.boins.server.s3.S3Handler;
import io.boins.server.s3.Xml;
import io.javalin.Javalin;
import io.javalin.http.Context;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The Boins server: an S3-compatible HTTP(S) interface plus an admin API on top of a
 * single shared {@link Boins} core.
 *
 * <p>Runs on virtual threads (configurable), serves S3 requests signed with AWS SigV4 —
 * including presigned URLs — and exposes {@code /_admin/*} for state, metrics and
 * recorded faults. Exceptional events are persisted by a {@link FaultStore} and delivered
 * to callbacks registered with {@link #onFault(Consumer)}.</p>
 */
public final class BoinsServer implements Closeable {

    private final BoinsServerConfig config;
    private final FaultStore faults;
    private final Boins core;
    private final BucketRegistry buckets;
    private final HttpMetrics httpMetrics = new HttpMetrics();
    private final Javalin app;
    private final ScheduledExecutorService scheduler;
    private final java.util.concurrent.atomic.AtomicLong inFlightRequests = new java.util.concurrent.atomic.AtomicLong();
    private volatile boolean shuttingDown;
    private volatile boolean closed;

    /** Opens the storage, binds the port and starts serving. */
    public static BoinsServer start(BoinsServerConfig config) throws IOException, BoinsException {
        config.validate();
        return new BoinsServer(config);
    }

    private BoinsServer(BoinsServerConfig config) throws IOException, BoinsException {
        this.config = config;
        FaultStore faultStore = null;
        Boins boins = null;
        BucketRegistry registry = null;
        try {
            faultStore = FaultStore.open(Path.of(config.faults.dir),
                    config.faults.stackFrames, config.faults.cooldownMillis, config.faults.maxFiles);
            boins = Boins.open(config.toBoinsOptions(faultStore));
            registry = openBuckets(config);
            this.faults = faultStore;
            this.core = boins;
            this.buckets = registry;
        } catch (IOException | BoinsException | RuntimeException e) {
            closeQuietly(registry);
            closeQuietly(boins);
            closeQuietly(faultStore);
            throw e;
        }

        S3Handler s3 = new S3Handler(core, buckets, faults, maxObjectSize(config),
                config.storage.fsyncMode == io.boins.core.BoinsOptions.FsyncMode.ALWAYS);
        AdminHandler admin = new AdminHandler(core, buckets, faults, httpMetrics, config.adminKey);

        this.app = Javalin.create(cfg -> {
            cfg.startup.showJavalinBanner = false;
            cfg.concurrency.useVirtualThreads = config.virtualThreads;
            cfg.http.compressionStrategy = io.javalin.compression.CompressionStrategy.NONE; // blobs are streamed as-is
            if (config.ssl.enabled) {
                cfg.jetty.addConnector((server, httpConfig) -> {
                    try {
                        return TlsSupport.httpsConnector(server, config.host, config.port,
                                Path.of(config.ssl.certificatePath), Path.of(config.ssl.privateKeyPath));
                    } catch (Exception e) {
                        throw new IllegalStateException("Failed to configure the HTTPS connector", e);
                    }
                });
            }

            cfg.routes.before(ctx -> {
                String requestId = HexFormat.of().toHexDigits(ThreadLocalRandom.current().nextLong());
                ctx.attribute("boins.requestId", requestId);
                ctx.header("x-amz-request-id", requestId);
                applyCorsHeaders(ctx);
                if (shuttingDown) {
                    throw new S3Exception(503, "ServiceUnavailable", "The server is shutting down.");
                }
                // Count the request as in-flight only after the shutdown gate, and mark it,
                // so the after-handler never decrements for rejected requests.
                inFlightRequests.incrementAndGet();
                ctx.attribute("boins.inFlight", Boolean.TRUE);
            });
            cfg.routes.after(ctx -> {
                if (ctx.attribute("boins.inFlight") != null) {
                    inFlightRequests.decrementAndGet();
                }
                String operation = ctx.attribute("boins.op");
                httpMetrics.record(operation != null ? operation : "Other", ctx.statusCode());
            });

            // CORS preflight for browser clients (presigned direct-to-storage uploads).
            // Preflight requests carry no SigV4 auth by design.
            if (!config.cors.allowedOrigins.isEmpty()) {
                cfg.routes.options("/", this::handleCorsPreflight);
                cfg.routes.options("/<path>", this::handleCorsPreflight);
            }

            // Admin interface. The /_admin prefix cannot collide with S3: bucket names
            // must start with a lowercase letter or digit.
            cfg.routes.get("/_admin/health", admin::health);
            cfg.routes.get("/_admin/state", admin::state);
            cfg.routes.get("/_admin/metrics", admin::metrics);
            cfg.routes.get("/_admin/faults", admin::faults);
            cfg.routes.get("/_admin/faults/{hash}", admin::faultReport);
            cfg.routes.post("/_admin/flush", admin::flush);

            // S3 API.
            cfg.routes.get("/", s3::listBuckets);
            cfg.routes.head("/{bucket}", s3::headBucket);
            cfg.routes.get("/{bucket}", s3::getBucket);
            cfg.routes.put("/{bucket}", s3::putBucket);
            cfg.routes.post("/{bucket}", s3::postBucket);
            cfg.routes.delete("/{bucket}", s3::deleteBucket);
            cfg.routes.get("/{bucket}/<key>", s3::getObject);
            cfg.routes.head("/{bucket}/<key>", s3::headObject);
            cfg.routes.put("/{bucket}/<key>", s3::putObject);
            cfg.routes.post("/{bucket}/<key>", s3::postObject);
            cfg.routes.delete("/{bucket}/<key>", s3::deleteObject);

            cfg.routes.exception(S3Handler.NotModified.class, (e, ctx) -> {
                // 304: status and headers are already prepared.
            });
            cfg.routes.exception(AdminHandler.AdminDisabled.class, (e, ctx) -> ctx.status(404));
            cfg.routes.exception(AdminHandler.Unauthorized.class,
                    (e, ctx) -> ctx.status(401).result("Missing or invalid admin key"));
            cfg.routes.exception(S3Exception.class, this::handleS3Exception);
            cfg.routes.exception(BoinsException.class, (e, ctx) ->
                    handleS3Exception(S3Handler.mapCoreException(e), ctx));
            cfg.routes.exception(Exception.class, (e, ctx) -> {
                faults.record(e, S3Handler.requestContext(ctx));
                handleS3Exception(S3Exception.internalError(
                        e.getMessage() != null ? e.getMessage() : e.getClass().getName()), ctx);
            });
        });

        if (config.ssl.enabled) {
            app.start();
        } else {
            app.start(config.host, config.port);
        }

        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "boins-server-maintenance");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::flushKeyIndexes,
                config.storage.fsyncIntervalMillis, config.storage.fsyncIntervalMillis, TimeUnit.MILLISECONDS);
        scheduler.scheduleWithFixedDelay(this::sweepMultipartUploads, 1L, 1L, TimeUnit.HOURS);
    }

    /** Adds CORS response headers when the request carries an allowed Origin. */
    private void applyCorsHeaders(Context ctx) {
        if (config.cors.allowedOrigins.isEmpty()) {
            return;
        }
        String origin = ctx.header("Origin");
        if (origin == null) {
            return;
        }
        if (config.cors.allowedOrigins.contains("*")) {
            ctx.header("Access-Control-Allow-Origin", "*");
        } else if (config.cors.allowedOrigins.contains(origin)) {
            ctx.header("Access-Control-Allow-Origin", origin);
            ctx.header("Vary", "Origin");
        } else {
            return;
        }
        ctx.header("Access-Control-Expose-Headers", "ETag, x-amz-request-id, Content-Range, Accept-Ranges");
    }

    private void handleCorsPreflight(Context ctx) {
        applyCorsHeaders(ctx);
        String requestedHeaders = ctx.header("Access-Control-Request-Headers");
        ctx.header("Access-Control-Allow-Methods", "GET, PUT, POST, DELETE, HEAD");
        ctx.header("Access-Control-Allow-Headers", requestedHeaders != null ? requestedHeaders : "*");
        ctx.header("Access-Control-Max-Age", "300");
        ctx.status(204);
    }

    private void handleS3Exception(S3Exception e, Context ctx) {
        if (e.status() >= 500) {
            faults.record(e, S3Handler.requestContext(ctx));
        }
        ctx.status(e.status());
        ctx.contentType("application/xml");
        ctx.result(Xml.error(e.code(), e.getMessage(), ctx.path(), ctx.attribute("boins.requestId")));
    }

    /** Registers a callback invoked on every exceptional event recorded by the server. */
    public void onFault(Consumer<FaultEvent> listener) {
        faults.addListener(listener);
    }

    /** The bound port (useful with port 0 in tests). */
    public int port() {
        return app.port();
    }

    public Boins core() {
        return core;
    }

    public FaultStore faults() {
        return faults;
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        // Graceful shutdown: reject new requests with 503, then drain in-flight ones.
        // On Linux under systemd this runs from the JVM shutdown hook on SIGTERM;
        // keep TimeoutStopSec above gracefulShutdownMillis.
        shuttingDown = true;
        long deadline = System.currentTimeMillis() + config.gracefulShutdownMillis;
        while (inFlightRequests.get() > 0L && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(20L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        scheduler.shutdownNow();
        app.stop();
        flushKeyIndexes();
        IOException first = null;
        first = close(buckets, first);
        first = close(core, first);
        faults.close();
        if (first != null) {
            throw first;
        }
    }

    // ---------------------------------------------------------------- internals

    private static BucketRegistry openBuckets(BoinsServerConfig config) throws IOException {
        List<Bucket> list = new ArrayList<>();
        try {
            for (BoinsServerConfig.BucketDef def : config.buckets) {
                list.add(new Bucket(def.name, def.accessKey, def.secretKey,
                        Path.of(config.bucketDataDir).resolve(def.name)));
            }
            return new BucketRegistry(list);
        } catch (IOException | RuntimeException e) {
            for (Bucket bucket : list) {
                closeQuietly(bucket);
            }
            throw e;
        }
    }

    private static long maxObjectSize(BoinsServerConfig config) {
        return 1L << config.storage.blobFileLimit;
    }

    private void flushKeyIndexes() {
        for (Bucket bucket : buckets.all()) {
            try {
                bucket.keys().force();
            } catch (IOException e) {
                faults.record(e, java.util.Map.of("operation", "flushKeyIndex", "bucket", bucket.name()));
            }
        }
    }

    private void sweepMultipartUploads() {
        for (Bucket bucket : buckets.all()) {
            bucket.multipartUploads().sweepExpired(MultipartUploads.DEFAULT_TTL_MILLIS);
        }
    }

    private static IOException close(Closeable closeable, IOException first) {
        try {
            closeable.close();
            return first;
        } catch (IOException e) {
            if (first != null) {
                first.addSuppressed(e);
                return first;
            }
            return e;
        }
    }

    private static void closeQuietly(AutoCloseable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Exception ignored) {
                // best-effort cleanup on failed construction
            }
        }
    }
}
