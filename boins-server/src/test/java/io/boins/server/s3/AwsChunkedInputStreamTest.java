package io.boins.server.s3;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AwsChunkedInputStreamTest {

    private static byte[] sha256(byte[] data) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(data);
    }

    private static AwsSigV4.SigningContext context() {
        byte[] signingKey = new byte[32];
        new Random(7L).nextBytes(signingKey);
        return new AwsSigV4.SigningContext(signingKey, "20260716T120000Z",
                "20260716/us-east-1/s3/aws4_request", "seed-signature");
    }

    /** Builds a correctly signed aws-chunked body for the given payload chunks. */
    private static byte[] signedBody(AwsSigV4.SigningContext ctx, byte[]... chunks) throws Exception {
        StringBuilder body = new StringBuilder();
        String previous = ctx.seedSignature();
        for (byte[] chunk : chunks) {
            String signature = AwsSigV4.chunkSignature(ctx, previous, sha256(chunk));
            body.append(Integer.toHexString(chunk.length)).append(";chunk-signature=").append(signature).append("\r\n");
            body.append(new String(chunk, StandardCharsets.ISO_8859_1)).append("\r\n");
            previous = signature;
        }
        String finalSignature = AwsSigV4.chunkSignature(ctx, previous, sha256(new byte[0]));
        body.append("0;chunk-signature=").append(finalSignature).append("\r\n\r\n");
        return body.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    @Test
    void decodesUnsignedTrailerBody() throws Exception {
        byte[] data = "Hello, chunked Boins!".getBytes(StandardCharsets.UTF_8);
        String body = Integer.toHexString(data.length) + "\r\n"
                + new String(data, StandardCharsets.ISO_8859_1) + "\r\n"
                + "0\r\n"
                + "x-amz-checksum-crc32:AAAAAA==\r\n"
                + "\r\n";
        try (AwsChunkedInputStream in = new AwsChunkedInputStream(
                new ByteArrayInputStream(body.getBytes(StandardCharsets.ISO_8859_1)), null)) {
            assertArrayEquals(data, in.readAllBytes());
        }
    }

    @Test
    void decodesMultipleChunks() throws Exception {
        byte[] a = new byte[70_000];
        byte[] b = new byte[123];
        new Random(1L).nextBytes(a);
        new Random(2L).nextBytes(b);
        String body = Integer.toHexString(a.length) + "\r\n" + new String(a, StandardCharsets.ISO_8859_1) + "\r\n"
                + Integer.toHexString(b.length) + "\r\n" + new String(b, StandardCharsets.ISO_8859_1) + "\r\n"
                + "0\r\n\r\n";
        try (AwsChunkedInputStream in = new AwsChunkedInputStream(
                new ByteArrayInputStream(body.getBytes(StandardCharsets.ISO_8859_1)), null)) {
            byte[] decoded = in.readAllBytes();
            byte[] expected = new byte[a.length + b.length];
            System.arraycopy(a, 0, expected, 0, a.length);
            System.arraycopy(b, 0, expected, a.length, b.length);
            assertArrayEquals(expected, decoded);
        }
    }

    @Test
    void verifiesSignedChunks() throws Exception {
        AwsSigV4.SigningContext ctx = context();
        byte[] chunk1 = new byte[10_000];
        byte[] chunk2 = new byte[500];
        new Random(3L).nextBytes(chunk1);
        new Random(4L).nextBytes(chunk2);
        byte[] body = signedBody(ctx, chunk1, chunk2);
        try (AwsChunkedInputStream in = new AwsChunkedInputStream(new ByteArrayInputStream(body), ctx)) {
            byte[] decoded = in.readAllBytes();
            byte[] expected = new byte[chunk1.length + chunk2.length];
            System.arraycopy(chunk1, 0, expected, 0, chunk1.length);
            System.arraycopy(chunk2, 0, expected, chunk1.length, chunk2.length);
            assertArrayEquals(expected, decoded);
        }
    }

    @Test
    void tamperedChunkDataIsDetected() throws Exception {
        AwsSigV4.SigningContext ctx = context();
        byte[] chunk = "attack at dawn".getBytes(StandardCharsets.UTF_8);
        byte[] body = signedBody(ctx, chunk);
        // flip one payload byte after signing
        String marker = "attack";
        String bodyText = new String(body, StandardCharsets.ISO_8859_1).replace("attack", "attacc");
        try (AwsChunkedInputStream in = new AwsChunkedInputStream(
                new ByteArrayInputStream(bodyText.getBytes(StandardCharsets.ISO_8859_1)), ctx)) {
            IOException e = assertThrows(IOException.class, in::readAllBytes);
            assertTrue(e.getMessage().contains("signature"), e.getMessage());
        }
        assertTrue(marker.length() > 0);
    }

    @Test
    void missingSignatureInSignedModeIsDetected() throws Exception {
        AwsSigV4.SigningContext ctx = context();
        String body = "5\r\nhello\r\n0\r\n\r\n"; // unsigned framing in signed mode
        try (AwsChunkedInputStream in = new AwsChunkedInputStream(
                new ByteArrayInputStream(body.getBytes(StandardCharsets.ISO_8859_1)), ctx)) {
            assertThrows(IOException.class, in::readAllBytes);
        }
    }

    @Test
    void truncatedBodyIsDetected() throws Exception {
        String body = "ff\r\nonly a few bytes";
        try (AwsChunkedInputStream in = new AwsChunkedInputStream(
                new ByteArrayInputStream(body.getBytes(StandardCharsets.ISO_8859_1)), null)) {
            assertThrows(IOException.class, in::readAllBytes);
        }
    }

    @Test
    void malformedSizeLineIsDetected() throws Exception {
        String body = "not-hex\r\ndata\r\n0\r\n\r\n";
        try (AwsChunkedInputStream in = new AwsChunkedInputStream(
                new ByteArrayInputStream(body.getBytes(StandardCharsets.ISO_8859_1)), null)) {
            assertThrows(IOException.class, in::readAllBytes);
        }
    }

    @Test
    void signedTrailerIsVerified() throws Exception {
        AwsSigV4.SigningContext ctx = context();
        byte[] chunk = "trailer test".getBytes(StandardCharsets.UTF_8);
        String chunkSig = AwsSigV4.chunkSignature(ctx, ctx.seedSignature(), sha256(chunk));
        String finalSig = AwsSigV4.chunkSignature(ctx, chunkSig, sha256(new byte[0]));
        String trailers = "x-amz-checksum-crc32:abcd\n";
        String trailerSig = AwsSigV4.trailerSignature(ctx, finalSig,
                sha256(trailers.getBytes(StandardCharsets.UTF_8)));
        String body = Integer.toHexString(chunk.length) + ";chunk-signature=" + chunkSig + "\r\n"
                + new String(chunk, StandardCharsets.ISO_8859_1) + "\r\n"
                + "0;chunk-signature=" + finalSig + "\r\n"
                + "x-amz-checksum-crc32:abcd\r\n"
                + "x-amz-trailer-signature:" + trailerSig + "\r\n"
                + "\r\n";
        try (AwsChunkedInputStream in = new AwsChunkedInputStream(
                new ByteArrayInputStream(body.getBytes(StandardCharsets.ISO_8859_1)), ctx)) {
            assertArrayEquals(chunk, in.readAllBytes());
        }
        // sanity: hex helper used by signatures stays stable
        assertTrue(HexFormat.of().formatHex(sha256(new byte[0])).startsWith("e3b0c442"));
    }
}
