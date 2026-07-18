package io.boins.server.bucket;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * All configured buckets. Buckets are defined in the server configuration and created at
 * startup; the S3 CreateBucket/DeleteBucket calls do not manage them (credentials belong
 * in configuration, not in unauthenticated-by-third-parties API calls).
 */
public final class BucketRegistry implements Closeable {

    private final Map<String, Bucket> byName;

    public BucketRegistry(List<Bucket> buckets) {
        Map<String, Bucket> map = new LinkedHashMap<>();
        for (Bucket bucket : buckets) {
            if (map.put(bucket.name(), bucket) != null) {
                throw new IllegalArgumentException("Duplicate bucket name: " + bucket.name());
            }
        }
        this.byName = map;
    }

    /** The bucket with this name, or {@code null}. */
    public Bucket byName(String name) {
        return byName.get(name);
    }

    /** The secret key of {@code accessKey}, or {@code null} if no bucket uses it. */
    public String secretKeyFor(String accessKey) {
        for (Bucket bucket : byName.values()) {
            if (bucket.accessKey().equals(accessKey)) {
                return bucket.secretKey();
            }
        }
        return null;
    }

    /** Buckets owned by {@code accessKey} (used by ListBuckets). */
    public List<Bucket> ownedBy(String accessKey) {
        List<Bucket> result = new ArrayList<>();
        for (Bucket bucket : byName.values()) {
            if (bucket.accessKey().equals(accessKey)) {
                result.add(bucket);
            }
        }
        return result;
    }

    public List<Bucket> all() {
        return List.copyOf(byName.values());
    }

    @Override
    public void close() throws IOException {
        IOException first = null;
        for (Bucket bucket : byName.values()) {
            try {
                bucket.close();
            } catch (IOException e) {
                if (first == null) {
                    first = e;
                } else {
                    first.addSuppressed(e);
                }
            }
        }
        if (first != null) {
            throw first;
        }
    }
}
