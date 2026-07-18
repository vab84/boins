package io.boins.server.s3;

import io.boins.core.BlobInfo;
import io.boins.core.BlobMetadata;
import io.boins.core.Boins;
import io.boins.core.BoinsException;
import io.boins.core.WriteResult;
import io.boins.core.faults.FaultStore;
import io.boins.server.bucket.Bucket;
import io.boins.server.bucket.BucketRegistry;
import io.boins.server.bucket.MultipartUploads;
import io.javalin.http.Context;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The S3-compatible API: buckets, objects, multipart uploads, presigned requests.
 *
 * <p>Every request is authenticated with AWS Signature V4 (header or presigned query).
 * Object keys map to blob ids through the bucket's {@link io.boins.server.bucket.KeyIndex};
 * content lives in the shared Boins core.</p>
 */
public final class S3Handler {

    public static final int MAX_KEY_LENGTH = 1_024;
    private static final int MAX_BATCH_DELETE = 1_000;
    private static final DateTimeFormatter ISO_MILLIS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter HTTP_DATE =
            DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC);

    private final Boins core;
    private final BucketRegistry buckets;
    private final FaultStore faults;
    private final long maxObjectSize;
    private final boolean fsyncAlways;

    public S3Handler(Boins core, BucketRegistry buckets, FaultStore faults, long maxObjectSize,
                     boolean fsyncAlways) {
        this.core = core;
        this.buckets = buckets;
        this.faults = faults;
        this.maxObjectSize = maxObjectSize;
        this.fsyncAlways = fsyncAlways;
    }

    // ---------------------------------------------------------------- service level

    /** {@code GET /} — ListBuckets. */
    public void listBuckets(Context ctx) {
        op(ctx, "ListBuckets");
        AwsSigV4.Result auth = authenticate(ctx);
        Xml xml = new Xml().openRoot("ListAllMyBucketsResult")
                .open("Owner").element("ID", "boins").element("DisplayName", "boins").close("Owner")
                .open("Buckets");
        for (Bucket bucket : buckets.ownedBy(auth.accessKey())) {
            xml.open("Bucket")
                    .element("Name", bucket.name())
                    .element("CreationDate", ISO_MILLIS.format(Instant.ofEpochMilli(bucket.createdMillis())))
                    .close("Bucket");
        }
        xml.close("Buckets").close("ListAllMyBucketsResult");
        sendXml(ctx, 200, xml);
    }

    // ---------------------------------------------------------------- bucket level

    /** {@code HEAD /{bucket}} — HeadBucket. */
    public void headBucket(Context ctx) {
        op(ctx, "HeadBucket");
        authorizeBucket(ctx);
        ctx.status(200);
    }

    /** {@code GET /{bucket}} — dispatches list / location / versioning / uploads. */
    public void getBucket(Context ctx) {
        Bucket bucket = authorizeBucket(ctx);
        Map<String, List<String>> query = ctx.queryParamMap();
        if (query.containsKey("location")) {
            op(ctx, "GetBucketLocation");
            sendXml(ctx, 200, new Xml().openRoot("LocationConstraint").close("LocationConstraint"));
        } else if (query.containsKey("versioning")) {
            op(ctx, "GetBucketVersioning");
            sendXml(ctx, 200, new Xml().openRoot("VersioningConfiguration").close("VersioningConfiguration"));
        } else if (query.containsKey("uploads")) {
            op(ctx, "ListMultipartUploads");
            listMultipartUploads(ctx, bucket);
        } else {
            op(ctx, "ListObjects");
            listObjects(ctx, bucket, "2".equals(ctx.queryParam("list-type")));
        }
    }

    /** {@code PUT /{bucket}} — CreateBucket; succeeds only for configured buckets. */
    public void putBucket(Context ctx) {
        op(ctx, "CreateBucket");
        authorizeBucket(ctx); // throws NoSuchBucket/AccessDenied for unknown buckets
        ctx.status(200);
        ctx.header("Location", "/" + ctx.pathParam("bucket"));
    }

    /** {@code DELETE /{bucket}} — buckets are configuration-managed. */
    public void deleteBucket(Context ctx) {
        op(ctx, "DeleteBucket");
        authorizeBucket(ctx);
        throw S3Exception.notImplemented("DeleteBucket (buckets are managed by the server configuration)");
    }

    /** {@code POST /{bucket}?delete} — DeleteObjects. */
    public void postBucket(Context ctx) {
        if (!ctx.queryParamMap().containsKey("delete")) {
            throw S3Exception.invalidRequest("Unsupported bucket POST.");
        }
        op(ctx, "DeleteObjects");
        Bucket bucket = authorizeBucket(ctx);
        DeleteRequest request = parseDeleteObjectsXml(ctx.bodyInputStream());
        if (request.keys.size() > MAX_BATCH_DELETE) {
            throw S3Exception.invalidRequest("DeleteObjects supports at most " + MAX_BATCH_DELETE + " keys.");
        }
        Xml xml = new Xml().openRoot("DeleteResult");
        for (String key : request.keys) {
            try {
                removeKey(bucket, key);
                if (!request.quiet) {
                    xml.open("Deleted").element("Key", key).close("Deleted");
                }
            } catch (Exception e) {
                recordFault(ctx, e);
                xml.open("Error")
                        .element("Key", key)
                        .element("Code", "InternalError")
                        .element("Message", e.getMessage())
                        .close("Error");
            }
        }
        xml.close("DeleteResult");
        sendXml(ctx, 200, xml);
    }

    // ---------------------------------------------------------------- object level

    /** {@code PUT /{bucket}/<key>} — PutObject, UploadPart or CopyObject. */
    public void putObject(Context ctx) throws IOException {
        Bucket bucket = authorizeBucket(ctx);
        String key = objectKey(ctx);
        AwsSigV4.Result auth = authenticate(ctx);
        if (ctx.queryParam("uploadId") != null) {
            op(ctx, "UploadPart");
            uploadPart(ctx, bucket, auth);
            return;
        }
        if (ctx.header("x-amz-copy-source") != null) {
            op(ctx, "CopyObject");
            copyObject(ctx, bucket, key, auth);
            return;
        }
        op(ctx, "PutObject");
        long length = contentLength(ctx);
        if (length > maxObjectSize) {
            throw S3Exception.entityTooLarge(length, maxObjectSize);
        }
        PayloadStream payload = payloadStream(ctx, auth);
        WriteResult result;
        try {
            result = core.write(payload.stream, length, metadataFromHeaders(ctx, key));
        } catch (BoinsException e) {
            throw mapCoreException(e);
        }
        verifyDigests(ctx, payload, result.etag(), () -> deleteQuietly(ctx, result.blobId()));
        replaceKey(ctx, bucket, key, result.blobId());
        ctx.header("ETag", quote(result.etag()));
        ctx.status(200);
    }

    /** {@code GET /{bucket}/<key>} — GetObject or ListParts. */
    public void getObject(Context ctx) {
        Bucket bucket = authorizeBucket(ctx);
        String key = objectKey(ctx);
        if (ctx.queryParam("uploadId") != null) {
            op(ctx, "ListParts");
            listParts(ctx, bucket, key);
            return;
        }
        op(ctx, "GetObject");
        serveObject(ctx, bucket, key, true);
    }

    /** {@code HEAD /{bucket}/<key>} — HeadObject. */
    public void headObject(Context ctx) {
        op(ctx, "HeadObject");
        Bucket bucket = authorizeBucket(ctx);
        serveObject(ctx, bucket, objectKey(ctx), false);
    }

    /** {@code POST /{bucket}/<key>} — CreateMultipartUpload or CompleteMultipartUpload. */
    public void postObject(Context ctx) throws IOException {
        Bucket bucket = authorizeBucket(ctx);
        String key = objectKey(ctx);
        if (ctx.queryParamMap().containsKey("uploads")) {
            op(ctx, "CreateMultipartUpload");
            MultipartUploads.Upload upload =
                    bucket.multipartUploads().create(key, metadataFromHeaders(ctx, key));
            sendXml(ctx, 200, new Xml().openRoot("InitiateMultipartUploadResult")
                    .element("Bucket", bucket.name())
                    .element("Key", key)
                    .element("UploadId", upload.id())
                    .close("InitiateMultipartUploadResult"));
            return;
        }
        String uploadId = ctx.queryParam("uploadId");
        if (uploadId != null) {
            op(ctx, "CompleteMultipartUpload");
            completeMultipartUpload(ctx, bucket, key, uploadId);
            return;
        }
        throw S3Exception.invalidRequest("Unsupported object POST.");
    }

    /** {@code DELETE /{bucket}/<key>} — DeleteObject or AbortMultipartUpload. */
    public void deleteObject(Context ctx) throws IOException {
        Bucket bucket = authorizeBucket(ctx);
        String key = objectKey(ctx);
        String uploadId = ctx.queryParam("uploadId");
        if (uploadId != null) {
            op(ctx, "AbortMultipartUpload");
            bucket.multipartUploads().abort(uploadId);
            ctx.status(204);
            return;
        }
        op(ctx, "DeleteObject");
        removeKey(bucket, key);
        ctx.status(204);
    }

    // ---------------------------------------------------------------- object internals

    private void serveObject(Context ctx, Bucket bucket, String key, boolean withBody) {
        Long blobId = bucket.keys().get(key);
        if (blobId == null) {
            throw S3Exception.noSuchKey(key);
        }
        BlobInfo info;
        try {
            info = core.info(blobId);
        } catch (BoinsException e) {
            throw mapCoreException(e);
        }
        if (info.deleted()) {
            throw S3Exception.noSuchKey(key);
        }
        String etag = quote(info.etag());
        checkConditionalHeaders(ctx, etag);

        ctx.header("ETag", etag);
        ctx.header("Last-Modified", HTTP_DATE.format(Instant.ofEpochMilli(info.createTime())));
        ctx.header("Accept-Ranges", "bytes");
        ctx.contentType(info.contentType() != null ? info.contentType() : "application/octet-stream");
        info.userMetadata().forEach((name, value) -> ctx.header("x-amz-meta-" + name, value));
        if (info.partCount() > 0) {
            ctx.header("x-amz-mp-parts-count", Integer.toString(info.partCount()));
        }
        // Standard S3 response-header overrides; presigned URLs sign these parameters,
        // so a download link can carry its own filename and cache policy.
        applyIfPresent(ctx, "response-content-type", ctx::contentType);
        applyIfPresent(ctx, "response-content-disposition", v -> ctx.header("Content-Disposition", v));
        applyIfPresent(ctx, "response-cache-control", v -> ctx.header("Cache-Control", v));
        applyIfPresent(ctx, "response-content-language", v -> ctx.header("Content-Language", v));
        applyIfPresent(ctx, "response-expires", v -> ctx.header("Expires", v));

        long[] range = parseRange(ctx.header("Range"), info.size());
        long offset = range != null ? range[0] : 0L;
        long length = range != null ? range[1] - range[0] + 1L : info.size();
        ctx.header("Content-Length", Long.toString(length));
        if (range != null) {
            ctx.status(206);
            ctx.header("Content-Range", "bytes " + range[0] + "-" + range[1] + "/" + info.size());
        } else {
            ctx.status(200);
        }
        if (withBody) {
            try {
                ctx.result(core.read(blobId, offset, length));
            } catch (BoinsException e) {
                throw mapCoreException(e);
            }
        }
    }

    private static void applyIfPresent(Context ctx, String queryParam, java.util.function.Consumer<String> apply) {
        String value = ctx.queryParam(queryParam);
        if (value != null && !value.isBlank()) {
            apply.accept(value);
        }
    }

    /**
     * Parses a single {@code bytes=} range. Returns {@code {start, end}} (inclusive) or
     * {@code null} to serve the full object (absent or unsupported Range headers are
     * ignored, matching S3 behaviour).
     */
    static long[] parseRange(String header, long size) {
        if (header == null || !header.startsWith("bytes=") || header.contains(",")) {
            return null;
        }
        String spec = header.substring("bytes=".length()).trim();
        int dash = spec.indexOf('-');
        if (dash < 0) {
            return null;
        }
        try {
            String startPart = spec.substring(0, dash).trim();
            String endPart = spec.substring(dash + 1).trim();
            long start;
            long end;
            if (startPart.isEmpty()) {
                // suffix range: last N bytes
                long suffix = Long.parseLong(endPart);
                if (suffix <= 0L || size == 0L) {
                    throw S3Exception.invalidRange(size);
                }
                start = Math.max(0L, size - suffix);
                end = size - 1L;
            } else {
                start = Long.parseLong(startPart);
                end = endPart.isEmpty() ? size - 1L : Math.min(Long.parseLong(endPart), size - 1L);
                if (start >= size || start > end) {
                    throw S3Exception.invalidRange(size);
                }
            }
            return new long[]{start, end};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void checkConditionalHeaders(Context ctx, String quotedEtag) {
        String ifMatch = ctx.header("If-Match");
        if (ifMatch != null && !etagMatches(ifMatch, quotedEtag)) {
            throw S3Exception.preconditionFailed();
        }
        String ifNoneMatch = ctx.header("If-None-Match");
        if (ifNoneMatch != null && etagMatches(ifNoneMatch, quotedEtag)) {
            ctx.header("ETag", quotedEtag);
            ctx.status(304);
            throw new NotModified();
        }
    }

    /** Control-flow marker: the response (304) is already fully prepared. */
    public static final class NotModified extends RuntimeException {
        NotModified() {
            super(null, null, false, false);
        }
    }

    private static boolean etagMatches(String headerValue, String quotedEtag) {
        for (String candidate : headerValue.split(",")) {
            String c = candidate.trim();
            if (c.equals("*") || c.equals(quotedEtag) || quote(c.replace("\"", "")).equals(quotedEtag)) {
                return true;
            }
        }
        return false;
    }

    private void copyObject(Context ctx, Bucket destBucket, String destKey, AwsSigV4.Result auth) {
        String rawSource = ctx.header("x-amz-copy-source");
        String source = URLDecoder.decode(rawSource, StandardCharsets.UTF_8);
        if (source.startsWith("/")) {
            source = source.substring(1);
        }
        int slash = source.indexOf('/');
        if (slash <= 0 || slash == source.length() - 1) {
            throw S3Exception.invalidArgument("Malformed x-amz-copy-source: " + rawSource);
        }
        String sourceBucketName = source.substring(0, slash);
        String sourceKey = source.substring(slash + 1);
        Bucket sourceBucket = buckets.byName(sourceBucketName);
        if (sourceBucket == null) {
            throw S3Exception.noSuchBucket(sourceBucketName);
        }
        if (!sourceBucket.accessKey().equals(auth.accessKey())) {
            throw S3Exception.accessDenied("The authenticated key does not own the source bucket.");
        }
        Long sourceBlobId = sourceBucket.keys().get(sourceKey);
        if (sourceBlobId == null) {
            throw S3Exception.noSuchKey(sourceKey);
        }
        try {
            BlobInfo sourceInfo = core.info(sourceBlobId);
            if (sourceInfo.deleted()) {
                throw S3Exception.noSuchKey(sourceKey);
            }
            boolean replaceMetadata = "REPLACE".equalsIgnoreCase(ctx.header("x-amz-metadata-directive"));
            BlobMetadata metadata = replaceMetadata
                    ? metadataFromHeaders(ctx, destKey)
                    : new BlobMetadata(destKey, sourceInfo.contentType(), sourceInfo.userMetadata());
            WriteResult result;
            try (InputStream in = core.read(sourceBlobId)) {
                result = core.write(in, sourceInfo.size(), metadata);
            }
            replaceKey(ctx, destBucket, destKey, result.blobId());
            sendXml(ctx, 200, new Xml().openRoot("CopyObjectResult")
                    .element("LastModified", ISO_MILLIS.format(Instant.now()))
                    .element("ETag", quote(result.etag()))
                    .close("CopyObjectResult"));
        } catch (BoinsException | IOException e) {
            throw mapCoreException(e);
        }
    }

    private void uploadPart(Context ctx, Bucket bucket, AwsSigV4.Result auth) throws IOException {
        String uploadId = ctx.queryParam("uploadId");
        int partNumber = parseInt(ctx.queryParam("partNumber"), "partNumber");
        if (partNumber < 1 || partNumber > 10_000) {
            throw S3Exception.invalidArgument("partNumber must be between 1 and 10000.");
        }
        MultipartUploads.Upload upload = bucket.multipartUploads().get(uploadId);
        if (upload == null) {
            throw S3Exception.noSuchUpload(uploadId);
        }
        PayloadStream payload = payloadStream(ctx, auth);
        MultipartUploads.Part part = bucket.multipartUploads().putPart(upload, partNumber, payload.stream);
        verifyDigests(ctx, payload, part.etagHex(), () -> {
            try {
                bucket.multipartUploads().dropPart(upload, partNumber);
            } catch (IOException e) {
                recordFault(ctx, e);
            }
        });
        ctx.header("ETag", quote(part.etagHex()));
        ctx.status(200);
    }

    private void completeMultipartUpload(Context ctx, Bucket bucket, String key, String uploadId)
            throws IOException {
        MultipartUploads.Upload upload = bucket.multipartUploads().get(uploadId);
        if (upload == null) {
            throw S3Exception.noSuchUpload(uploadId);
        }
        List<Map.Entry<Integer, String>> requestedParts = parseCompleteMultipartXml(ctx.bodyInputStream());
        MultipartUploads.Completed completed = bucket.multipartUploads().complete(
                upload, requestedParts, S3Exception::invalidPart, S3Exception::invalidPartOrder);
        if (completed.totalSize() > maxObjectSize) {
            throw S3Exception.entityTooLarge(completed.totalSize(), maxObjectSize);
        }
        WriteResult result;
        try (InputStream in = bucket.multipartUploads().concatenatedStream(completed)) {
            result = core.write(in, completed.totalSize(), upload.metadata(),
                    completed.md5OfPartMd5s(), completed.parts().size());
        } catch (BoinsException e) {
            throw mapCoreException(e);
        }
        replaceKey(ctx, bucket, key, result.blobId());
        bucket.multipartUploads().abort(uploadId); // cleanup of temp part files
        sendXml(ctx, 200, new Xml().openRoot("CompleteMultipartUploadResult")
                .element("Location", "/" + bucket.name() + "/" + key)
                .element("Bucket", bucket.name())
                .element("Key", key)
                .element("ETag", quote(result.etag()))
                .close("CompleteMultipartUploadResult"));
    }

    private void listParts(Context ctx, Bucket bucket, String key) {
        String uploadId = ctx.queryParam("uploadId");
        MultipartUploads.Upload upload = bucket.multipartUploads().get(uploadId);
        if (upload == null) {
            throw S3Exception.noSuchUpload(uploadId);
        }
        List<MultipartUploads.Part> parts = new ArrayList<>(upload.parts().values());
        parts.sort(Comparator.comparingInt(MultipartUploads.Part::number));
        Xml xml = new Xml().openRoot("ListPartsResult")
                .element("Bucket", bucket.name())
                .element("Key", key)
                .element("UploadId", uploadId)
                .element("IsTruncated", "false");
        for (MultipartUploads.Part part : parts) {
            xml.open("Part")
                    .element("PartNumber", part.number())
                    .element("ETag", quote(part.etagHex()))
                    .element("Size", part.size())
                    .close("Part");
        }
        xml.close("ListPartsResult");
        sendXml(ctx, 200, xml);
    }

    private void listMultipartUploads(Context ctx, Bucket bucket) {
        Xml xml = new Xml().openRoot("ListMultipartUploadsResult")
                .element("Bucket", bucket.name())
                .element("IsTruncated", "false");
        for (MultipartUploads.Upload upload : bucket.multipartUploads().all()) {
            xml.open("Upload")
                    .element("Key", upload.key())
                    .element("UploadId", upload.id())
                    .element("Initiated", ISO_MILLIS.format(Instant.ofEpochMilli(upload.initiatedMillis())))
                    .close("Upload");
        }
        xml.close("ListMultipartUploadsResult");
        sendXml(ctx, 200, xml);
    }

    // ---------------------------------------------------------------- listing

    private void listObjects(Context ctx, Bucket bucket, boolean v2) {
        String prefix = orEmpty(ctx.queryParam("prefix"));
        String delimiter = orEmpty(ctx.queryParam("delimiter"));
        boolean urlEncode = "url".equals(ctx.queryParam("encoding-type"));
        int maxKeys = clampMaxKeys(ctx.queryParam("max-keys"));

        String after;
        String continuationToken = null;
        String startAfter = null;
        String marker = null;
        if (v2) {
            continuationToken = ctx.queryParam("continuation-token");
            startAfter = ctx.queryParam("start-after");
            after = continuationToken != null ? decodeToken(continuationToken) : orEmpty(startAfter);
        } else {
            marker = orEmpty(ctx.queryParam("marker"));
            after = marker;
        }

        S3Listing.Result result = S3Listing.list(bucket.keys().sortedView(), prefix, delimiter, after, maxKeys,
                (key, blobId) -> {
                    try {
                        BlobInfo info = core.info(blobId);
                        return info.deleted() ? null : new S3Listing.Item(key, info.size(), info.createTime(), info.etag());
                    } catch (BoinsException e) {
                        recordFault(ctx, e);
                        return null;
                    }
                });

        Xml xml = new Xml().openRoot("ListBucketResult")
                .element("Name", bucket.name())
                .element("Prefix", encodeIf(urlEncode, prefix))
                .element("MaxKeys", maxKeys)
                .element("IsTruncated", result.truncated());
        if (!delimiter.isEmpty()) {
            xml.element("Delimiter", encodeIf(urlEncode, delimiter));
        }
        if (urlEncode) {
            xml.element("EncodingType", "url");
        }
        if (v2) {
            xml.element("KeyCount", result.contents().size() + result.commonPrefixes().size());
            if (continuationToken != null) {
                xml.element("ContinuationToken", continuationToken);
            }
            if (startAfter != null) {
                xml.element("StartAfter", encodeIf(urlEncode, startAfter));
            }
            if (result.truncated()) {
                xml.element("NextContinuationToken", encodeToken(result.nextToken()));
            }
        } else {
            xml.element("Marker", encodeIf(urlEncode, marker));
            if (result.truncated()) {
                xml.element("NextMarker", encodeIf(urlEncode, result.nextToken()));
            }
        }
        for (S3Listing.Item item : result.contents()) {
            xml.open("Contents")
                    .element("Key", encodeIf(urlEncode, item.key()))
                    .element("LastModified", ISO_MILLIS.format(Instant.ofEpochMilli(item.lastModified())))
                    .element("ETag", quote(item.etag()))
                    .element("Size", item.size())
                    .element("StorageClass", "STANDARD")
                    .close("Contents");
        }
        for (String commonPrefix : result.commonPrefixes()) {
            xml.open("CommonPrefixes")
                    .element("Prefix", encodeIf(urlEncode, commonPrefix))
                    .close("CommonPrefixes");
        }
        xml.close("ListBucketResult");
        sendXml(ctx, 200, xml);
    }

    private static int clampMaxKeys(String param) {
        if (param == null) {
            return 1_000;
        }
        try {
            return Math.max(0, Math.min(Integer.parseInt(param), 1_000));
        } catch (NumberFormatException e) {
            throw S3Exception.invalidArgument("Invalid max-keys: " + param);
        }
    }

    private static String encodeToken(String token) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(token.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeToken(String token) {
        try {
            return new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw S3Exception.invalidArgument("Invalid continuation-token.");
        }
    }

    private static String encodeIf(boolean urlEncode, String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        return urlEncode ? AwsSigV4.uriEncode(value, false) : value;
    }

    // ---------------------------------------------------------------- shared plumbing

    /** Sets the operation label used by HTTP metrics. */
    private static void op(Context ctx, String operation) {
        ctx.attribute("boins.op", operation);
    }

    private AwsSigV4.Result authenticate(Context ctx) {
        AwsSigV4.Result cached = ctx.attribute("boins.auth");
        if (cached != null) {
            return cached;
        }
        AwsSigV4.Request request = new AwsSigV4.Request(
                ctx.method().name(),
                ctx.req().getRequestURI(),
                ctx.req().getQueryString(),
                ctx::header);
        AwsSigV4.Result result = AwsSigV4.verify(request, buckets::secretKeyFor);
        ctx.attribute("boins.auth", result);
        return result;
    }

    /** Authenticates the request and checks that the key owns the addressed bucket. */
    private Bucket authorizeBucket(Context ctx) {
        AwsSigV4.Result auth = authenticate(ctx);
        String name = ctx.pathParam("bucket");
        Bucket bucket = buckets.byName(name);
        if (bucket == null) {
            throw S3Exception.noSuchBucket(name);
        }
        if (!bucket.accessKey().equals(auth.accessKey())) {
            throw S3Exception.accessDenied("The authenticated key does not own bucket " + name + ".");
        }
        return bucket;
    }

    private static String objectKey(Context ctx) {
        String key = ctx.pathParam("key");
        if (key.isEmpty()) {
            throw S3Exception.invalidArgument("Empty object key.");
        }
        if (key.getBytes(StandardCharsets.UTF_8).length > MAX_KEY_LENGTH) {
            throw S3Exception.invalidArgument("Object key is longer than " + MAX_KEY_LENGTH + " bytes.");
        }
        return key;
    }

    private record PayloadStream(InputStream stream, MessageDigest sha256, String expectedSha256Hex) {
    }

    /** Wraps the request body according to the payload mode negotiated by the signature. */
    private PayloadStream payloadStream(Context ctx, AwsSigV4.Result auth) {
        InputStream raw = ctx.bodyInputStream();
        String mode = auth.payloadSha256();
        return switch (mode) {
            case AwsSigV4.STREAMING_SIGNED, AwsSigV4.STREAMING_SIGNED_TRAILER ->
                    new PayloadStream(new AwsChunkedInputStream(raw, auth.signingContext()), null, null);
            case AwsSigV4.STREAMING_UNSIGNED_TRAILER ->
                    new PayloadStream(new AwsChunkedInputStream(raw, null), null, null);
            case AwsSigV4.UNSIGNED_PAYLOAD -> new PayloadStream(raw, null, null);
            default -> {
                // The signature covers a concrete payload hash: verify it while streaming.
                MessageDigest digest = sha256();
                yield new PayloadStream(new DigestInputStream(raw, digest), digest, mode);
            }
        };
    }

    /**
     * Verifies x-amz-content-sha256 (when the signature covered a concrete payload hash)
     * and the optional Content-MD5 header after the body was consumed. On mismatch, runs
     * {@code discardStored} to remove the already-persisted content, then fails the request.
     */
    private void verifyDigests(Context ctx, PayloadStream payload, String actualMd5Hex, Runnable discardStored) {
        if (payload.sha256 != null) {
            String actual = HexFormat.of().formatHex(payload.sha256.digest());
            if (!actual.equalsIgnoreCase(payload.expectedSha256Hex)) {
                discardStored.run();
                throw S3Exception.badDigest(payload.expectedSha256Hex, actual);
            }
        }
        String contentMd5 = ctx.header("Content-MD5");
        if (contentMd5 != null && !contentMd5.isBlank()) {
            String expectedHex;
            try {
                expectedHex = HexFormat.of().formatHex(Base64.getDecoder().decode(contentMd5.trim()));
            } catch (IllegalArgumentException e) {
                discardStored.run();
                throw S3Exception.invalidArgument("Content-MD5 is not valid base64.");
            }
            if (!expectedHex.equalsIgnoreCase(actualMd5Hex)) {
                discardStored.run();
                throw new S3Exception(400, "BadDigest",
                        "The Content-MD5 you specified did not match what we received.");
            }
        }
    }

    private void deleteQuietly(Context ctx, long blobId) {
        try {
            core.delete(blobId);
        } catch (BoinsException e) {
            recordFault(ctx, e);
        }
    }

    /** Points {@code key} at a new blob and deletes the previous blob, if any. */
    private void replaceKey(Context ctx, Bucket bucket, String key, long blobId) throws IOException {
        Long previous = bucket.keys().put(key, blobId);
        if (fsyncAlways) {
            bucket.keys().force();
        }
        if (previous != null && previous != blobId) {
            deleteQuietly(ctx, previous);
        }
    }

    /** Removes {@code key} and deletes its blob. Missing keys are a silent no-op (S3 semantics). */
    private void removeKey(Bucket bucket, String key) throws IOException {
        Long previous = bucket.keys().remove(key);
        if (previous != null) {
            if (fsyncAlways) {
                bucket.keys().force();
            }
            try {
                core.delete(previous);
            } catch (BoinsException e) {
                faults.record(e, Map.of("operation", "removeKey", "key", key));
            }
        }
    }

    private BlobMetadata metadataFromHeaders(Context ctx, String key) {
        Map<String, String> userMetadata = new LinkedHashMap<>();
        for (Map.Entry<String, String> header : new TreeMap<>(ctx.headerMap()).entrySet()) {
            String name = header.getKey().toLowerCase();
            if (name.startsWith("x-amz-meta-")) {
                userMetadata.put(name.substring("x-amz-meta-".length()), header.getValue());
            }
        }
        return new BlobMetadata(key, ctx.header("Content-Type"), userMetadata);
    }

    private long contentLength(Context ctx) {
        String decoded = ctx.header("x-amz-decoded-content-length");
        String raw = decoded != null ? decoded : ctx.header("Content-Length");
        if (raw == null) {
            throw new S3Exception(411, "MissingContentLength", "You must provide the Content-Length HTTP header.");
        }
        try {
            long length = Long.parseLong(raw.trim());
            if (length < 0L) {
                throw new NumberFormatException();
            }
            return length;
        } catch (NumberFormatException e) {
            throw S3Exception.invalidArgument("Invalid content length: " + raw);
        }
    }

    public static S3Exception mapCoreException(Exception e) {
        return switch (e) {
            case io.boins.core.BlobNotFoundException n -> S3Exception.noSuchKey("blobId=" + n.blobId());
            case io.boins.core.BlobDeletedException d -> S3Exception.noSuchKey("blobId=" + d.blobId());
            case io.boins.core.StorageFullException f ->
                    new S3Exception(507, "InsufficientStorage", f.getMessage());
            default -> S3Exception.internalError(e.getMessage() != null ? e.getMessage() : e.getClass().getName());
        };
    }

    private void recordFault(Context ctx, Exception e) {
        faults.record(e, requestContext(ctx));
    }

    /** Snapshot of the request (method, path, headers) stored with recorded exceptions. */
    public static Map<String, String> requestContext(Context ctx) {
        Map<String, String> context = new LinkedHashMap<>();
        context.put("http_method", ctx.method().name());
        context.put("http_path", ctx.path());
        String query = ctx.req().getQueryString();
        if (query != null) {
            context.put("http_query", query);
        }
        context.put("remote_address", ctx.req().getRemoteAddr());
        ctx.headerMap().forEach((name, value) -> context.put("header_" + name.toLowerCase(), value));
        return context;
    }

    private static void sendXml(Context ctx, int status, Xml xml) {
        ctx.status(status);
        ctx.contentType("application/xml");
        ctx.result(xml.toString());
    }

    private static String quote(String etag) {
        return "\"" + etag + "\"";
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    private static int parseInt(String value, String name) {
        if (value == null) {
            throw S3Exception.invalidArgument("Missing " + name + ".");
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw S3Exception.invalidArgument("Invalid " + name + ": " + value);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    // ---------------------------------------------------------------- request XML parsing

    private record DeleteRequest(List<String> keys, boolean quiet) {
    }

    private DeleteRequest parseDeleteObjectsXml(InputStream body) {
        List<String> keys = new ArrayList<>();
        boolean quiet = false;
        try {
            XMLStreamReader reader = secureXmlReader(body);
            StringBuilder text = new StringBuilder();
            boolean insideObject = false;
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    text.setLength(0);
                    if ("Object".equals(reader.getLocalName())) {
                        insideObject = true;
                    }
                } else if (event == XMLStreamConstants.CHARACTERS) {
                    // Character data may arrive in several events (e.g. around entities):
                    // accumulate until the element ends.
                    text.append(reader.getText());
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    String element = reader.getLocalName();
                    String value = text.toString().trim();
                    if (insideObject && "Key".equals(element)) {
                        keys.add(value);
                    } else if ("Quiet".equals(element)) {
                        quiet = Boolean.parseBoolean(value);
                    } else if ("Object".equals(element)) {
                        insideObject = false;
                    }
                    text.setLength(0);
                }
            }
            return new DeleteRequest(keys, quiet);
        } catch (XMLStreamException e) {
            throw S3Exception.malformedXml();
        }
    }

    private List<Map.Entry<Integer, String>> parseCompleteMultipartXml(InputStream body) {
        List<Map.Entry<Integer, String>> parts = new ArrayList<>();
        try {
            XMLStreamReader reader = secureXmlReader(body);
            StringBuilder text = new StringBuilder();
            Integer partNumber = null;
            String etag = null;
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    text.setLength(0);
                    if ("Part".equals(reader.getLocalName())) {
                        partNumber = null;
                        etag = null;
                    }
                } else if (event == XMLStreamConstants.CHARACTERS) {
                    text.append(reader.getText());
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    String element = reader.getLocalName();
                    String value = text.toString().trim();
                    if ("PartNumber".equals(element)) {
                        partNumber = Integer.parseInt(value);
                    } else if ("ETag".equals(element)) {
                        etag = value;
                    } else if ("Part".equals(element)) {
                        if (partNumber == null || etag == null) {
                            throw S3Exception.malformedXml();
                        }
                        parts.add(new AbstractMap.SimpleEntry<>(partNumber, etag));
                    }
                    text.setLength(0);
                }
            }
            return parts;
        } catch (XMLStreamException | NumberFormatException e) {
            throw S3Exception.malformedXml();
        }
    }

    private static XMLStreamReader secureXmlReader(InputStream body) throws XMLStreamException {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.IS_COALESCING, true);
        return factory.createXMLStreamReader(body != null ? body : new ByteArrayInputStream(new byte[0]));
    }
}
