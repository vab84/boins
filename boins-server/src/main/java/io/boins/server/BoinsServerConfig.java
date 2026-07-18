package io.boins.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.boins.core.BoinsOptions;
import io.boins.core.faults.FaultStore;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Boins server configuration. Bound 1:1 from the YAML config file in standalone mode,
 * or built programmatically in embedded mode.
 */
public class BoinsServerConfig {

    private static final Pattern BUCKET_NAME = Pattern.compile("^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$");

    /** Bind host. */
    public String host = "0.0.0.0";
    /** Listen port (HTTP or HTTPS depending on {@link Ssl#enabled}). */
    public int port = 9000;
    /** Serve requests on virtual threads. */
    public boolean virtualThreads = true;
    /** Key for the {@code /_admin} interface; admin endpoints are disabled when blank. */
    public String adminKey;
    /**
     * How long {@code close()} waits for in-flight requests to finish before stopping.
     * New requests are rejected with 503 while draining. Keep systemd's
     * {@code TimeoutStopSec} above this value.
     */
    public long gracefulShutdownMillis = 15_000L;
    public Cors cors = new Cors();
    public Ssl ssl = new Ssl();
    public Storage storage = new Storage();
    public Faults faults = new Faults();
    /** Directory for per-bucket data (key indexes, multipart temp files). */
    public String bucketDataDir = "./data/buckets";
    public List<BucketDef> buckets = new ArrayList<>();

    /**
     * CORS for browser clients (needed for direct-to-storage uploads via presigned URLs).
     * Empty {@code allowedOrigins} disables CORS entirely; {@code "*"} allows any origin.
     */
    public static class Cors {
        public List<String> allowedOrigins = new ArrayList<>();
    }

    public static class Ssl {
        public boolean enabled = false;
        /** PEM file with the certificate (leaf first, then intermediates). */
        public String certificatePath;
        /** PEM file with the PKCS#8 private key ({@code BEGIN PRIVATE KEY}). */
        public String privateKeyPath;
    }

    public static class Storage {
        public List<Repo> repositories = new ArrayList<>();
        public int blobFileLimit = 36;
        public long minBlobBytes = 1024L;
        public BoinsOptions.FsyncMode fsyncMode = BoinsOptions.FsyncMode.INTERVAL;
        public long fsyncIntervalMillis = 1_000L;
        public long minRepositoryFreeBytes = 1L << 30;
        /** Defaults to {@code <first repository>/free-cells.boins}. */
        public String freeCellsFile;
        /** Defaults to {@code <first repository>/metrics.boins}. */
        public String metricsFile;
        public long metricsFlushIntervalMillis = 10_000L;

        public static class Repo {
            public String dir;
            public long blobIdOffset = 0L;
            public BoinsOptions.DiskType diskType = BoinsOptions.DiskType.SSD;
        }
    }

    public static class Faults {
        public String dir = "./data/faults";
        public int stackFrames = FaultStore.DEFAULT_STACK_FRAMES;
        public long cooldownMillis = FaultStore.DEFAULT_COOLDOWN_MILLIS;
        public int maxFiles = FaultStore.DEFAULT_MAX_FILES;
    }

    public static class BucketDef {
        public String name;
        public String accessKey;
        public String secretKey;
    }

    /** Commented default configuration written by {@link #loadOrCreate} for a first run. */
    static final String DEFAULT_YAML = """
            # Boins server options.
            # This file was created with default values. The server starts, but serves no
            # buckets yet — fill in the 'buckets' section and restart.
            # Relative paths are resolved against the server's working directory.

            host: 0.0.0.0                    # bind address
            port: 9000                       # listen port (HTTP, or HTTPS when ssl.enabled)
            virtualThreads: true             # serve each request on a virtual thread
            adminKey: ""                     # key for /_admin/*; blank disables the admin API
            gracefulShutdownMillis: 15000    # how long to drain in-flight requests on stop

            cors:
              # Origins allowed for browser clients (presigned direct-to-storage uploads).
              # Empty list = CORS disabled. Example: ["https://app.example.com"] or ["*"]
              allowedOrigins: []

            ssl:
              enabled: false                 # true = HTTPS on the same port
              # certificatePath: ./tls/cert.pem   # PEM chain, leaf certificate first
              # privateKeyPath: ./tls/key.pem     # PKCS#8 private key (BEGIN PRIVATE KEY)

            storage:
              blobFileLimit: 36              # blob file size limit as a power of two (2^36 = 64 GiB)
              minBlobBytes: 1024             # append-candidate cutoff, see the Boins Book
              fsyncMode: INTERVAL            # ALWAYS | INTERVAL | NEVER
              fsyncIntervalMillis: 1000      # flush period for fsyncMode INTERVAL, in milliseconds
              minRepositoryFreeBytes: 1073741824   # a repo stops accepting inserts below this free space (1 GiB)
              metricsFlushIntervalMillis: 10000    # how often cumulative metrics are persisted
              repositories:                  # one entry per physical disk
                - dir: ./data/repo1          # repository directory, created if missing
                  blobIdOffset: 0            # first blob id of this repository; NEVER change it later
                  diskType: SSD              # SSD | HDD (write concurrency strategy)

            faults:
              dir: ./data/faults             # exception reports are stored here as flat files
              stackFrames: 5                 # stack trace lines in the deduplication key
              cooldownMillis: 60000          # min interval between rewrites of one report file
              maxFiles: 1000                 # cap on report files; the oldest fault is evicted

            bucketDataDir: ./data/buckets    # per-bucket data (key indexes, multipart temp files)

            buckets: []                      # the server starts with no buckets; example:
            # buckets:
            #   - name: my-bucket
            #     accessKey: my-access-key
            #     secretKey: my-secret-key
            """;

    public static BoinsServerConfig loadYaml(Path file) throws IOException {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        BoinsServerConfig config = mapper.readValue(file.toFile(), BoinsServerConfig.class);
        config.validate();
        return config;
    }

    /**
     * Loads the configuration from {@code file}, first creating a commented default file
     * (parent directories included) when it does not exist — mirroring the behaviour of
     * {@code Boins.open(Path)} in embedded mode. An existing but invalid file still fails:
     * a broken configuration is never silently replaced.
     */
    public static BoinsServerConfig loadOrCreate(Path file) throws IOException {
        if (!java.nio.file.Files.exists(file)) {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                java.nio.file.Files.createDirectories(parent);
            }
            java.nio.file.Files.writeString(file, DEFAULT_YAML, java.nio.charset.StandardCharsets.UTF_8);
        }
        return loadYaml(file);
    }

    public void validate() {
        if (port < 0 || port > 65_535) {
            // port 0 binds an ephemeral port (useful for tests and embedded mode)
            throw new IllegalArgumentException("Invalid port: " + port);
        }
        if (gracefulShutdownMillis < 0L) {
            throw new IllegalArgumentException("gracefulShutdownMillis must be >= 0");
        }
        if (storage.repositories.isEmpty()) {
            throw new IllegalArgumentException("At least one storage repository must be configured");
        }
        if (ssl.enabled && (blank(ssl.certificatePath) || blank(ssl.privateKeyPath))) {
            throw new IllegalArgumentException("SSL is enabled but certificatePath/privateKeyPath is missing");
        }
        Set<String> names = new HashSet<>();
        for (BucketDef bucket : buckets) {
            if (blank(bucket.name) || blank(bucket.accessKey) || blank(bucket.secretKey)) {
                throw new IllegalArgumentException("Every bucket needs a name, accessKey and secretKey");
            }
            if (!BUCKET_NAME.matcher(bucket.name).matches()) {
                throw new IllegalArgumentException("Invalid bucket name (S3 naming rules): " + bucket.name);
            }
            if (!names.add(bucket.name)) {
                throw new IllegalArgumentException("Duplicate bucket name: " + bucket.name);
            }
        }
    }

    /** Translates the storage section into core options. */
    public BoinsOptions toBoinsOptions(FaultStore faultStore) {
        BoinsOptions.Builder builder = BoinsOptions.builder()
                .blobFileLimit(storage.blobFileLimit)
                .minBlobBytes(storage.minBlobBytes)
                .fsyncMode(storage.fsyncMode)
                .fsyncIntervalMillis(storage.fsyncIntervalMillis)
                .minRepositoryFreeBytes(storage.minRepositoryFreeBytes)
                .metricsFlushIntervalMillis(storage.metricsFlushIntervalMillis)
                .faultStore(faultStore);
        for (Storage.Repo repo : storage.repositories) {
            builder.addRepository(Path.of(repo.dir), repo.blobIdOffset, repo.diskType);
        }
        if (!blank(storage.freeCellsFile)) {
            builder.freeCellsFile(Path.of(storage.freeCellsFile));
        }
        if (!blank(storage.metricsFile)) {
            builder.metricsFile(Path.of(storage.metricsFile));
        }
        return builder.build();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
