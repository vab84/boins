package io.boins.server.s3;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for SigV4 plumbing. Full end-to-end signature verification against the real
 * AWS SDK signer is covered by {@code S3IntegrationTest}.
 */
class AwsSigV4Test {

    private static final DateTimeFormatter AMZ = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");

    private static String amzDate(long offsetMillis) {
        return LocalDateTime.ofEpochSecond((System.currentTimeMillis() + offsetMillis) / 1000L, 0, ZoneOffset.UTC)
                .format(AMZ);
    }

    private static AwsSigV4.Request request(String method, String path, String query, Map<String, String> headers) {
        Function<String, String> header = name -> {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                if (e.getKey().equalsIgnoreCase(name)) {
                    return e.getValue();
                }
            }
            return null;
        };
        return new AwsSigV4.Request(method, path, query, header);
    }

    @Test
    void uriEncodeFollowsAwsRules() {
        assertEquals("abc-XYZ_0.9~", AwsSigV4.uriEncode("abc-XYZ_0.9~", true));
        assertEquals("a%20b", AwsSigV4.uriEncode("a b", true));
        assertEquals("a%2Fb", AwsSigV4.uriEncode("a/b", true));
        assertEquals("a/b", AwsSigV4.uriEncode("a/b", false));
        assertEquals("%D0%BC%D0%B8%D1%80", AwsSigV4.uriEncode("мир", true));
        assertEquals("%2B%3D%26", AwsSigV4.uriEncode("+=&", true));
    }

    @Test
    void parseQueryDecodesKeysAndValues() {
        Map<String, List<String>> query = AwsSigV4.parseQuery("a=1&b=%2Fx&b=2&empty=&flag");
        assertEquals(List.of("1"), query.get("a"));
        assertEquals(List.of("/x", "2"), query.get("b"));
        assertEquals(List.of(""), query.get("empty"));
        assertEquals(List.of(""), query.get("flag"));
        assertTrue(AwsSigV4.parseQuery(null).isEmpty());
    }

    @Test
    void missingAuthenticationIsRejected() {
        S3Exception e = assertThrows(S3Exception.class, () -> AwsSigV4.verify(
                request("GET", "/bucket/key", null, Map.of()), accessKey -> "secret"));
        assertEquals(403, e.status());
        assertEquals("AccessDenied", e.code());
    }

    @Test
    void skewedRequestTimeIsRejected() {
        String amzDate = amzDate(-30 * 60 * 1000L); // 30 minutes in the past
        Map<String, String> headers = Map.of(
                "Authorization", "AWS4-HMAC-SHA256 Credential=AK/20260101/us-east-1/s3/aws4_request, "
                        + "SignedHeaders=host, Signature=deadbeef",
                "x-amz-date", amzDate,
                "x-amz-content-sha256", AwsSigV4.UNSIGNED_PAYLOAD);
        S3Exception e = assertThrows(S3Exception.class, () -> AwsSigV4.verify(
                request("GET", "/b/k", null, headers), accessKey -> "secret"));
        assertEquals("RequestTimeTooSkewed", e.code());
    }

    @Test
    void expiredPresignedRequestIsRejected() {
        String created = amzDate(-10 * 60 * 1000L); // created 10 minutes ago, expires in 60s
        String query = "X-Amz-Algorithm=AWS4-HMAC-SHA256"
                + "&X-Amz-Credential=AK%2F20260101%2Fus-east-1%2Fs3%2Faws4_request"
                + "&X-Amz-Date=" + created
                + "&X-Amz-Expires=60"
                + "&X-Amz-SignedHeaders=host"
                + "&X-Amz-Signature=deadbeef";
        S3Exception e = assertThrows(S3Exception.class, () -> AwsSigV4.verify(
                request("GET", "/b/k", query, Map.of("Host", "localhost")), accessKey -> "secret"));
        assertEquals(403, e.status());
        assertTrue(e.getMessage().contains("expired"));
    }

    @Test
    void unknownAccessKeyIsRejected() {
        Map<String, String> headers = Map.of(
                "Authorization", "AWS4-HMAC-SHA256 Credential=UNKNOWN/20260101/us-east-1/s3/aws4_request, "
                        + "SignedHeaders=host, Signature=deadbeef",
                "x-amz-date", amzDate(0),
                "x-amz-content-sha256", AwsSigV4.UNSIGNED_PAYLOAD);
        S3Exception e = assertThrows(S3Exception.class, () -> AwsSigV4.verify(
                request("GET", "/b/k", null, headers), accessKey -> null));
        assertEquals("InvalidAccessKeyId", e.code());
    }

    @Test
    void malformedCredentialScopeIsRejected() {
        Map<String, String> headers = Map.of(
                "Authorization", "AWS4-HMAC-SHA256 Credential=AK/only-two, SignedHeaders=host, Signature=x",
                "x-amz-date", amzDate(0),
                "x-amz-content-sha256", AwsSigV4.UNSIGNED_PAYLOAD);
        S3Exception e = assertThrows(S3Exception.class, () -> AwsSigV4.verify(
                request("GET", "/b/k", null, headers), accessKey -> "secret"));
        assertEquals("InvalidRequest", e.code());
    }

    @Test
    void wrongServiceInScopeIsRejected() {
        Map<String, String> headers = Map.of(
                "Authorization", "AWS4-HMAC-SHA256 Credential=AK/20260101/us-east-1/dynamodb/aws4_request, "
                        + "SignedHeaders=host, Signature=x",
                "x-amz-date", amzDate(0),
                "x-amz-content-sha256", AwsSigV4.UNSIGNED_PAYLOAD);
        S3Exception e = assertThrows(S3Exception.class, () -> AwsSigV4.verify(
                request("GET", "/b/k", null, headers), accessKey -> "secret"));
        assertTrue(e.getMessage().contains("s3"));
    }

    @Test
    void wrongSignatureIsRejected() {
        Map<String, String> headers = Map.of(
                "Authorization", "AWS4-HMAC-SHA256 Credential=AK/20260101/us-east-1/s3/aws4_request, "
                        + "SignedHeaders=host;x-amz-date, Signature=" + "0".repeat(64),
                "Host", "localhost:9000",
                "x-amz-date", amzDate(0),
                "x-amz-content-sha256", AwsSigV4.UNSIGNED_PAYLOAD);
        S3Exception e = assertThrows(S3Exception.class, () -> AwsSigV4.verify(
                request("GET", "/b/k", null, headers), accessKey -> "secret"));
        assertEquals("SignatureDoesNotMatch", e.code());
    }
}
