package io.boins.server.s3;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * AWS Signature Version 4 verification for the S3 service.
 *
 * <p>Supports both authentication styles:</p>
 * <ul>
 *   <li>header-based ({@code Authorization: AWS4-HMAC-SHA256 Credential=...}), and</li>
 *   <li>presigned query ({@code ?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Signature=...}) —
 *       the "client-signed upload/download" / direct-to-storage pattern.</li>
 * </ul>
 *
 * <p>The signing key is derived from the date/region/service found in the request's own
 * credential scope, so clients may use any region name.</p>
 */
public final class AwsSigV4 {

    public static final String ALGORITHM = "AWS4-HMAC-SHA256";
    public static final String UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD";
    public static final String STREAMING_SIGNED = "STREAMING-AWS4-HMAC-SHA256-PAYLOAD";
    public static final String STREAMING_SIGNED_TRAILER = "STREAMING-AWS4-HMAC-SHA256-PAYLOAD-TRAILER";
    public static final String STREAMING_UNSIGNED_TRAILER = "STREAMING-UNSIGNED-PAYLOAD-TRAILER";

    private static final DateTimeFormatter AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");
    private static final long MAX_CLOCK_SKEW_MILLIS = 15 * 60 * 1000L;

    private AwsSigV4() {
    }

    /** Resolves an access key id to its secret key, or {@code null} if unknown. */
    @FunctionalInterface
    public interface SecretKeyResolver {
        String secretKeyFor(String accessKey);
    }

    /**
     * Everything needed to verify the signatures of streamed {@code aws-chunked} payloads
     * after the request signature itself has been verified.
     *
     * @param signingKey    derived SigV4 signing key
     * @param amzDate       request timestamp in amz-date format
     * @param scope         credential scope ({@code date/region/s3/aws4_request})
     * @param seedSignature the verified request signature (first chunk chains from it)
     */
    public record SigningContext(byte[] signingKey, String amzDate, String scope, String seedSignature) {
    }

    /**
     * Verification result.
     *
     * @param accessKey      authenticated access key id
     * @param payloadSha256  value of {@code x-amz-content-sha256} (or UNSIGNED-PAYLOAD for presigned)
     * @param signingContext context for chunked payload verification
     */
    public record Result(String accessKey, String payloadSha256, SigningContext signingContext) {
    }

    /**
     * The subset of an HTTP request needed for verification.
     *
     * @param method      HTTP method, uppercase
     * @param encodedPath the raw, still-encoded request path exactly as sent by the client
     * @param rawQuery    the raw query string (may be {@code null})
     * @param header      case-insensitive header accessor returning {@code null} when absent
     */
    public record Request(String method, String encodedPath, String rawQuery, Function<String, String> header) {
    }

    /**
     * Verifies the request signature.
     *
     * @throws S3Exception if authentication is missing, malformed, expired or wrong
     */
    public static Result verify(Request request, SecretKeyResolver resolver) {
        Map<String, List<String>> query = parseQuery(request.rawQuery());
        if (ALGORITHM.equals(first(query, "X-Amz-Algorithm"))) {
            return verifyPresigned(request, query, resolver);
        }
        String authorization = request.header().apply("Authorization");
        if (authorization == null || authorization.isBlank()) {
            throw S3Exception.accessDenied("Missing Authorization header or presigned query parameters.");
        }
        return verifyHeaderAuth(request, query, authorization, resolver);
    }

    // ---------------------------------------------------------------- header auth

    private static Result verifyHeaderAuth(Request request, Map<String, List<String>> query,
                                           String authorization, SecretKeyResolver resolver) {
        if (!authorization.startsWith(ALGORITHM)) {
            throw S3Exception.invalidRequest("Unsupported Authorization scheme. Expected " + ALGORITHM + ".");
        }
        Map<String, String> authParams = parseAuthorization(authorization.substring(ALGORITHM.length()).trim());
        Scope scope = Scope.parse(authParams.get("Credential"));
        String signedHeaders = authParams.get("SignedHeaders");
        String providedSignature = authParams.get("Signature");
        if (signedHeaders == null || providedSignature == null) {
            throw S3Exception.invalidRequest("Authorization header is missing SignedHeaders or Signature.");
        }

        String amzDate = request.header().apply("x-amz-date");
        if (amzDate == null) {
            amzDate = request.header().apply("Date");
        }
        checkClockSkew(amzDate);

        String payloadSha256 = request.header().apply("x-amz-content-sha256");
        if (payloadSha256 == null) {
            throw S3Exception.invalidRequest("Missing required header: x-amz-content-sha256.");
        }

        String secretKey = resolver.secretKeyFor(scope.accessKey);
        if (secretKey == null) {
            throw S3Exception.invalidAccessKeyId(scope.accessKey);
        }

        String canonicalRequest = canonicalRequest(request, query, signedHeaders, payloadSha256);
        byte[] signingKey = signingKey(secretKey, scope);
        String expected = signature(signingKey, amzDate, scope.scopeString(), canonicalRequest);
        if (!constantTimeEquals(expected, providedSignature)) {
            throw S3Exception.signatureDoesNotMatch();
        }
        return new Result(scope.accessKey, payloadSha256,
                new SigningContext(signingKey, amzDate, scope.scopeString(), expected));
    }

    // ---------------------------------------------------------------- presigned query auth

    private static Result verifyPresigned(Request request, Map<String, List<String>> query,
                                          SecretKeyResolver resolver) {
        Scope scope = Scope.parse(first(query, "X-Amz-Credential"));
        String amzDate = first(query, "X-Amz-Date");
        String expiresStr = first(query, "X-Amz-Expires");
        String signedHeaders = first(query, "X-Amz-SignedHeaders");
        String providedSignature = first(query, "X-Amz-Signature");
        if (amzDate == null || expiresStr == null || signedHeaders == null || providedSignature == null) {
            throw S3Exception.invalidRequest("Presigned request is missing required X-Amz-* parameters.");
        }
        long created = parseAmzDate(amzDate);
        long expires;
        try {
            expires = Long.parseLong(expiresStr);
        } catch (NumberFormatException e) {
            throw S3Exception.invalidArgument("Invalid X-Amz-Expires value: " + expiresStr);
        }
        long now = System.currentTimeMillis();
        if (created - now > MAX_CLOCK_SKEW_MILLIS) {
            throw S3Exception.requestTimeTooSkewed();
        }
        if (now > created + expires * 1000L) {
            throw S3Exception.expiredPresignedRequest();
        }

        String secretKey = resolver.secretKeyFor(scope.accessKey);
        if (secretKey == null) {
            throw S3Exception.invalidAccessKeyId(scope.accessKey);
        }

        // The signature parameter itself is excluded from the canonical query string.
        Map<String, List<String>> canonicalQuery = new TreeMap<>(query);
        canonicalQuery.remove("X-Amz-Signature");
        String canonicalRequest = canonicalRequest(request, canonicalQuery, signedHeaders, UNSIGNED_PAYLOAD);
        byte[] signingKey = signingKey(secretKey, scope);
        String expected = signature(signingKey, amzDate, scope.scopeString(), canonicalRequest);
        if (!constantTimeEquals(expected, providedSignature)) {
            throw S3Exception.signatureDoesNotMatch();
        }
        return new Result(scope.accessKey, UNSIGNED_PAYLOAD,
                new SigningContext(signingKey, amzDate, scope.scopeString(), expected));
    }

    // ---------------------------------------------------------------- chunked payload signatures

    /** Computes the expected signature of one {@code aws-chunked} chunk. */
    public static String chunkSignature(SigningContext ctx, String previousSignature, byte[] chunkSha256) {
        String stringToSign = "AWS4-HMAC-SHA256-PAYLOAD\n"
                + ctx.amzDate() + "\n"
                + ctx.scope() + "\n"
                + previousSignature + "\n"
                + sha256Hex(new byte[0]) + "\n"
                + HexFormat.of().formatHex(chunkSha256);
        return HexFormat.of().formatHex(hmacSha256(ctx.signingKey(), stringToSign));
    }

    /** Computes the expected signature of the {@code aws-chunked} trailer section. */
    public static String trailerSignature(SigningContext ctx, String previousSignature, byte[] trailerSha256) {
        String stringToSign = "AWS4-HMAC-SHA256-TRAILER\n"
                + ctx.amzDate() + "\n"
                + ctx.scope() + "\n"
                + previousSignature + "\n"
                + HexFormat.of().formatHex(trailerSha256);
        return HexFormat.of().formatHex(hmacSha256(ctx.signingKey(), stringToSign));
    }

    // ---------------------------------------------------------------- canonical request

    private static String canonicalRequest(Request request, Map<String, List<String>> query,
                                           String signedHeaders, String payloadSha256) {
        StringBuilder canonical = new StringBuilder(512);
        canonical.append(request.method()).append('\n');
        String path = request.encodedPath();
        canonical.append(path == null || path.isEmpty() ? "/" : path).append('\n');
        canonical.append(canonicalQueryString(query)).append('\n');
        for (String name : signedHeaders.split(";")) {
            String value = request.header().apply(name);
            canonical.append(name.toLowerCase()).append(':')
                    .append(value == null ? "" : normalizeHeaderValue(value)).append('\n');
        }
        canonical.append('\n').append(signedHeaders).append('\n').append(payloadSha256);
        return canonical.toString();
    }

    private static String canonicalQueryString(Map<String, List<String>> query) {
        record Pair(String key, String value) {
        }
        List<Pair> pairs = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : query.entrySet()) {
            for (String v : e.getValue()) {
                pairs.add(new Pair(uriEncode(e.getKey(), true), uriEncode(v, true)));
            }
        }
        pairs.sort((a, b) -> {
            int byKey = a.key.compareTo(b.key);
            return byKey != 0 ? byKey : a.value.compareTo(b.value);
        });
        StringBuilder sb = new StringBuilder(128);
        for (Pair p : pairs) {
            if (!sb.isEmpty()) {
                sb.append('&');
            }
            sb.append(p.key).append('=').append(p.value);
        }
        return sb.toString();
    }

    /** Trims and collapses sequential spaces, per the SigV4 specification. */
    private static String normalizeHeaderValue(String value) {
        return value.trim().replaceAll(" +", " ");
    }

    /**
     * AWS uri-encoding: unreserved characters {@code A-Z a-z 0-9 - . _ ~} stay as-is,
     * everything else becomes uppercase percent escapes; space is {@code %20}.
     */
    static String uriEncode(String s, boolean encodeSlash) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        byte[] utf8 = s.getBytes(StandardCharsets.UTF_8);
        for (byte b : utf8) {
            char c = (char) (b & 0xFF);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '.' || c == '_' || c == '~'
                    || (c == '/' && !encodeSlash)) {
                sb.append(c);
            } else {
                sb.append('%').append(String.format("%02X", b & 0xFF));
            }
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- pieces

    private record Scope(String accessKey, String date, String region, String service) {

        static Scope parse(String credential) {
            if (credential == null) {
                throw S3Exception.invalidRequest("Missing Credential in the authorization data.");
            }
            String[] parts = credential.split("/");
            if (parts.length != 5 || !"aws4_request".equals(parts[4])) {
                throw S3Exception.invalidRequest("Malformed Credential: expected "
                        + "accessKey/date/region/service/aws4_request.");
            }
            if (!"s3".equals(parts[3])) {
                throw S3Exception.invalidRequest("Credential scope service must be s3, got: " + parts[3]);
            }
            return new Scope(parts[0], parts[1], parts[2], parts[3]);
        }

        String scopeString() {
            return date + "/" + region + "/" + service + "/aws4_request";
        }
    }

    private static Map<String, String> parseAuthorization(String params) {
        Map<String, String> result = new TreeMap<>();
        for (String part : params.split(",")) {
            int eq = part.indexOf('=');
            if (eq > 0) {
                result.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
            }
        }
        return result;
    }

    /** Parses a raw query string; keys/values are percent-decoded ({@code +} means space). */
    static Map<String, List<String>> parseQuery(String rawQuery) {
        Map<String, List<String>> query = new TreeMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return query;
        }
        for (String token : rawQuery.split("&")) {
            if (token.isEmpty()) {
                continue;
            }
            int eq = token.indexOf('=');
            String key = eq < 0 ? token : token.substring(0, eq);
            String value = eq < 0 ? "" : token.substring(eq + 1);
            query.computeIfAbsent(URLDecoder.decode(key, StandardCharsets.UTF_8), k -> new ArrayList<>())
                    .add(URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return query;
    }

    private static String first(Map<String, List<String>> query, String key) {
        List<String> values = query.get(key);
        return values == null || values.isEmpty() ? null : values.getFirst();
    }

    private static void checkClockSkew(String amzDate) {
        long requestTime = parseAmzDate(amzDate);
        if (Math.abs(System.currentTimeMillis() - requestTime) > MAX_CLOCK_SKEW_MILLIS) {
            throw S3Exception.requestTimeTooSkewed();
        }
    }

    private static long parseAmzDate(String amzDate) {
        if (amzDate == null) {
            throw S3Exception.invalidRequest("Missing request date (x-amz-date).");
        }
        try {
            return LocalDateTime.parse(amzDate, AMZ_DATE).toInstant(ZoneOffset.UTC).toEpochMilli();
        } catch (DateTimeParseException e) {
            throw S3Exception.invalidRequest("Invalid amz-date: " + amzDate);
        }
    }

    private static byte[] signingKey(String secretKey, Scope scope) {
        byte[] kDate = hmacSha256(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), scope.date);
        byte[] kRegion = hmacSha256(kDate, scope.region);
        byte[] kService = hmacSha256(kRegion, scope.service);
        return hmacSha256(kService, "aws4_request");
    }

    private static String signature(byte[] signingKey, String amzDate, String scope, String canonicalRequest) {
        String stringToSign = ALGORITHM + "\n"
                + amzDate + "\n"
                + scope + "\n"
                + sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hmacSha256(signingKey, stringToSign));
    }

    static byte[] hmacSha256(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is unavailable", e);
        }
    }

    static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
