package io.boins.server.s3;

/**
 * An S3 protocol error: carries the S3 error code, HTTP status and a human message.
 * Rendered as the standard S3 XML error body.
 */
public class S3Exception extends RuntimeException {

    private final int status;
    private final String code;

    public S3Exception(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    // Factories for the errors used across the S3 layer.

    public static S3Exception noSuchBucket(String bucket) {
        return new S3Exception(404, "NoSuchBucket", "The specified bucket does not exist: " + bucket);
    }

    public static S3Exception noSuchKey(String key) {
        return new S3Exception(404, "NoSuchKey", "The specified key does not exist: " + key);
    }

    public static S3Exception noSuchUpload(String uploadId) {
        return new S3Exception(404, "NoSuchUpload", "The specified multipart upload does not exist: " + uploadId);
    }

    public static S3Exception accessDenied(String message) {
        return new S3Exception(403, "AccessDenied", message);
    }

    public static S3Exception signatureDoesNotMatch() {
        return new S3Exception(403, "SignatureDoesNotMatch",
                "The request signature we calculated does not match the signature you provided.");
    }

    public static S3Exception invalidAccessKeyId(String accessKey) {
        return new S3Exception(403, "InvalidAccessKeyId",
                "The AWS access key id you provided does not exist in our records: " + accessKey);
    }

    public static S3Exception requestTimeTooSkewed() {
        return new S3Exception(403, "RequestTimeTooSkewed",
                "The difference between the request time and the server time is too large.");
    }

    public static S3Exception expiredPresignedRequest() {
        return new S3Exception(403, "AccessDenied", "Request has expired");
    }

    public static S3Exception invalidRequest(String message) {
        return new S3Exception(400, "InvalidRequest", message);
    }

    public static S3Exception invalidArgument(String message) {
        return new S3Exception(400, "InvalidArgument", message);
    }

    public static S3Exception malformedXml() {
        return new S3Exception(400, "MalformedXML",
                "The XML you provided was not well-formed or did not validate against our published schema.");
    }

    public static S3Exception invalidPart(String message) {
        return new S3Exception(400, "InvalidPart", message);
    }

    public static S3Exception invalidPartOrder() {
        return new S3Exception(400, "InvalidPartOrder",
                "The list of parts was not in ascending order. Parts must be ordered by part number.");
    }

    public static S3Exception entityTooLarge(long size, long max) {
        return new S3Exception(400, "EntityTooLarge",
                "Your proposed upload of " + size + " bytes exceeds the maximum allowed object size of " + max + " bytes.");
    }

    public static S3Exception badDigest(String expected, String actual) {
        return new S3Exception(400, "BadDigest",
                "The Content-SHA256 you specified (" + expected + ") did not match what we computed (" + actual + ").");
    }

    public static S3Exception invalidRange(long size) {
        return new S3Exception(416, "InvalidRange",
                "The requested range is not satisfiable; object size is " + size + " bytes.");
    }

    public static S3Exception preconditionFailed() {
        return new S3Exception(412, "PreconditionFailed",
                "At least one of the pre-conditions you specified did not hold.");
    }

    public static S3Exception notImplemented(String what) {
        return new S3Exception(501, "NotImplemented", what + " is not implemented by Boins.");
    }

    public static S3Exception internalError(String message) {
        return new S3Exception(500, "InternalError", message);
    }
}
