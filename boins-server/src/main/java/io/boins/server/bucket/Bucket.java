package io.boins.server.bucket;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Runtime state of one bucket: its credentials and its key index.
 * Blob content lives in the shared Boins core; the bucket only owns the key namespace.
 */
public final class Bucket implements Closeable {

    private final String name;
    private final String accessKey;
    private final String secretKey;
    private final KeyIndex keys;
    private final MultipartUploads multipartUploads;
    private final long createdMillis;

    public Bucket(String name, String accessKey, String secretKey, Path bucketDir) throws IOException {
        this.name = name;
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.keys = KeyIndex.open(bucketDir.resolve("keys.boins"));
        this.multipartUploads = new MultipartUploads(bucketDir.resolve("multipart"));
        this.createdMillis = System.currentTimeMillis();
    }

    public String name() {
        return name;
    }

    public String accessKey() {
        return accessKey;
    }

    public String secretKey() {
        return secretKey;
    }

    public KeyIndex keys() {
        return keys;
    }

    public MultipartUploads multipartUploads() {
        return multipartUploads;
    }

    public long createdMillis() {
        return createdMillis;
    }

    @Override
    public void close() throws IOException {
        keys.close();
        multipartUploads.close();
    }
}
