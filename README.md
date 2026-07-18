# Boins

**Minimal, fast, S3-compatible blob storage for the JVM.**

Boins packs binary objects (blobs) together into a few large files instead of creating one
file per object. A compact fixed-size index maps a blob id to its exact byte range, so every
read is a single positional disk read — no directory walks, no per-object file handles, no
filesystem metadata pressure even at hundreds of millions of objects.

- **Embedded mode** — a zero-dependency Java library (`boins-core`).
- **Server mode** — an S3-compatible HTTP(S) API (`boins-server`), verified against the
  official AWS SDK. Any S3 client works, including presigned URLs for direct-to-storage
  uploads and downloads.

Licensed under [Apache-2.0](LICENSE). Requires Java 24+.

## Why Boins

| | |
|---|---|
| **Minimal** | The core has zero runtime dependencies. The server adds only Javalin (Jetty) and Jackson. No database, no WAL, no external services. |
| **Fast** | Streaming writes go straight from the socket into the blob file (MD5 computed on the fly, no temp files). Reads are positional and lock-free. ~980 MiB/s multi-threaded writes on a laptop NVMe. |
| **Space-efficient** | Cells of deleted blobs are reused by later writes of a similar size, with a size-dependent waste tolerance (a 1 GiB cell tolerates ≤ 7 % waste). Aborted uploads are reclaimed too. |
| **Crash-safe by design** | No WAL: the free-cell registry is allowed to go stale and is cross-checked against the index on startup; bucket key indexes are CRC-framed and self-truncate torn tails. Worst case after a power cut is a bounded space leak — never a double-allocated cell. |
| **S3-compatible** | SigV4 (header + presigned query), streaming `aws-chunked` payloads with signed chunks and trailing checksums, multipart uploads, ListObjects V1/V2, Range, conditional requests, CopyObject, batch delete. |
| **Observable** | Built-in metrics (traffic counters, read/write throughput) persisted across restarts; a flat-file exception store with deduplication and rate limiting; a JSON admin API and programmatic fault callbacks. |

## Storage design in one minute

```
data/repo1/
├─ manifest.boins        format version + blob id offset (self-describing, relocatable)
├─ index.boins           80-byte records: file, position, size, cell, times, key, md5…
├─ blob.0.boins          blob content back to back, up to 2^blobFileLimit bytes per file
├─ heap.boins            append-only string heap: object keys, user metadata
├─ free-cells.boins      reusable cells of deleted blobs (best-fit with waste tolerance)
└─ metrics.boins         persisted counters
```

- `blobId = repositoryOffset + recordOrdinal` — lookup is arithmetic, not search.
- Multiple repositories (disks) per storage; inserts pick a disk randomly, **weighted by free
  space**, so disks fill evenly; reads route by id range.
- Durability is a dial: `fsyncMode: ALWAYS | INTERVAL | NEVER`.
- On SSD, concurrent writers stream into disjoint reserved regions in parallel; on HDD,
  chunk-level serialization keeps the access pattern sequential without slow-client convoys.

The full manual — architecture, formats, crash-consistency model, operations — is in the
bilingual (EN/RU) **[Boins Book](docs/book/index.html)**.

## Quick start: server

```yaml
# boins.yaml
host: 0.0.0.0
port: 9000
adminKey: change-me
storage:
  fsyncMode: INTERVAL
  repositories:
    - dir: ./data/repo1
      blobIdOffset: 0
      diskType: SSD
buckets:
  - name: my-bucket
    accessKey: boins-access-key
    secretKey: boins-secret-key
```

```bash
# development
./gradlew :boins-server:run --args="boins.yaml"

# production: self-contained distribution with a launcher script
./gradlew :boins-server:installDist   # → build/install/boins-server/bin/boins-server
```

The server shuts down gracefully on SIGTERM (new requests get 503, in-flight requests are
drained, everything is flushed) — a ready-made systemd unit and a full Linux deployment
guide are in the Book. Browser clients (direct-to-storage uploads) are supported via the
`cors.allowedOrigins` setting.

On a first run without a config file, the server creates a fully commented default
`boins.yaml` (with no buckets, so nothing is exposed with well-known credentials) and
starts with it — fill in the `buckets` section and restart.

Use it with any S3 client:

```bash
aws s3 cp report.pdf s3://my-bucket/docs/report.pdf \
    --endpoint-url http://localhost:9000
```

```java
S3Client s3 = S3Client.builder()
        .endpointOverride(URI.create("http://localhost:9000"))
        .region(Region.US_EAST_1)
        .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create("boins-access-key", "boins-secret-key")))
        .forcePathStyle(true)
        .build();
s3.putObject(b -> b.bucket("my-bucket").key("photos/cat.jpg"), RequestBody.fromFile(path));
```

Presigned URLs (direct-to-storage) work exactly as with AWS: generate them with the SDK's
`S3Presigner` and hand them to browsers or other services — no Boins credentials needed on
their side.

HTTPS: set `ssl.enabled: true` with PEM certificate/PKCS#8 key paths (see the Book).

## Quick start: embedded

```java
try (Boins boins = Boins.open(BoinsOptions.builder()
        .addRepository(Path.of("/data/repo1"), 0L, DiskType.SSD)
        .blobFileLimit(36)                    // 64 GiB per blob file
        .fsyncMode(FsyncMode.INTERVAL)
        .build())) {

    WriteResult w = boins.write(Path.of("report.pdf"),
            new BlobMetadata("docs/report.pdf", "application/pdf"));

    try (InputStream in = boins.read(w.blobId())) { in.transferTo(out); }

    BlobInfo info = boins.info(w.blobId());   // size, etag, key, user metadata…
    boins.delete(w.blobId());                 // the cell becomes reusable
}
```

Prefer configuration files? `Boins.open(Path.of("./boins/boins-options.yaml"))` opens the
storage from a YAML file — and creates a commented default file (parent directories
included) on first run.

The instance is fully thread-safe: open one per storage and share it across all
application threads (never open the same directories from two instances or processes).

## Admin interface

`/_admin/*`, authenticated with `Authorization: Bearer <adminKey>` or `X-Boins-Admin-Key`:

| Endpoint | Purpose |
|---|---|
| `GET /_admin/health` | liveness (no auth) |
| `GET /_admin/state` | repositories, free cells, per-bucket object counts |
| `GET /_admin/metrics` | storage counters + throughput, HTTP counters per S3 operation |
| `GET /_admin/faults` | deduplicated exception reports |
| `GET /_admin/faults/{hash}` | full report: stack trace + HTTP request context |
| `POST /_admin/flush` | force fsync |

Programmatic callbacks: `server.onFault(event -> …)` fires on every exceptional event,
including rate-limited duplicates.

## Performance

Measured on a developer laptop (Windows 11, 8 CPUs, NVMe SSD, Java 24), loopback network.
Reproduce with `./gradlew test -Dboins.bench=true --tests "*Benchmark"`.

**Embedded core**

| Scenario | Throughput | Ops/s |
|---|---:|---:|
| write 4 KiB blobs (fsync=NEVER) | 145 MiB/s | 37 100 |
| write 64 KiB blobs (fsync=NEVER) | 507 MiB/s | 8 100 |
| write 64 KiB blobs, 8 threads | 979 MiB/s | 15 700 |
| write 1 MiB blobs | 609 MiB/s | 609 |
| write 256 KiB into reused cells | 629 MiB/s | 2 500 |
| read 1 MiB, sequential / random (page cache) | 4.6 / 5.2 GiB/s | — |
| write 64 KiB (fsync=INTERVAL 1 s) | 383 MiB/s | 6 100 |
| write 64 KiB (fsync=ALWAYS) | 8.4 MiB/s | 135 |

**S3 over HTTP (AWS SDK v2 client)**

| Scenario | Throughput | Ops/s |
|---|---:|---:|
| PUT 1 MiB, 1 thread | 71 MiB/s | 71 |
| GET 1 MiB, 1 thread | 138 MiB/s | 138 |
| PUT 64 KiB, 8 threads | 36 MiB/s | 574 |
| GET 64 KiB, 8 threads | 69 MiB/s | 1 103 |

HTTP numbers include SigV4 verification, `aws-chunked` decoding and server-side MD5; at small
object sizes the per-request overhead of the SDK client dominates, not the storage engine.

## Building

```bash
./gradlew build          # compile + all tests
./gradlew test           # unit + integration tests (AWS SDK against a live server)
```

The integration suite starts a real Boins server and drives it with the official AWS SDK for
Java v2 — including multipart uploads, presigned URLs, tampered-signature rejection and an
HTTPS round trip with a PEM certificate.

## Project layout

```
boins-core/     storage engine, metrics, fault store   (zero dependencies)
boins-server/   S3 API, admin API, TLS, YAML config    (Javalin, Jackson)
docs/book/      Boins Book — the bilingual manual
```

## Compatibility notes

- Buckets are defined in the server configuration; S3 `CreateBucket`/`DeleteBucket` do not
  manage them (credentials belong in config, not in API calls).
- Versioning, ACLs/policies, lifecycle rules and object lock are not implemented.
- The 5 MiB minimum multipart part size is not enforced; in-flight multipart uploads do not
  survive a server restart (clients retry, as SDKs do for `NoSuchUpload`).

## TODO

- [ ] Run more load testing — long-running, multi-hour soak tests on server-grade hardware
      (HDD arrays, multiple disks, high client concurrency, large datasets that exceed RAM).
- [ ] Background garbage collection for blobs orphaned by crash windows between the key
      index and the core (currently a bounded, observable space leak).
- [ ] Optional key-index spill-to-disk mode for buckets with billions of objects.
- [ ] ListObjectsV2 `fetch-owner`, object tagging, and `x-amz-checksum-*` response validation.
- [ ] Multi-node deployments: a thin client-side host selector (weighted by free space) for
      sharding buckets across independent Boins servers.

## Contributing

Issues and pull requests are welcome. The codebase is intentionally small — start with the
[Boins Book](docs/book/index.html), then read `boins-core` top to bottom (it's one afternoon).

## License

[Apache License 2.0](LICENSE) · © 2026 Vasily Buter
