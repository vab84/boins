package io.boins.server.s3;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Decodes an {@code aws-chunked} request body:
 *
 * <pre>
 * hex-size[;chunk-signature=sig]\r\n
 * &lt;data&gt;\r\n
 * ...
 * 0[;chunk-signature=sig]\r\n
 * [trailer-name: value\r\n]*
 * [x-amz-trailer-signature:sig\r\n]
 * \r\n
 * </pre>
 *
 * <p>Covers all payload modes the AWS SDKs send: {@code STREAMING-AWS4-HMAC-SHA256-PAYLOAD}
 * (signed chunks), {@code STREAMING-AWS4-HMAC-SHA256-PAYLOAD-TRAILER} (signed chunks +
 * trailing checksum) and {@code STREAMING-UNSIGNED-PAYLOAD-TRAILER} (unsigned chunks +
 * trailing checksum). When a {@link AwsSigV4.SigningContext} is supplied, every chunk
 * signature is verified as the stream is consumed; a mismatch aborts the stream with an
 * {@link IOException}, which in turn aborts and reclaims the blob write.</p>
 */
public final class AwsChunkedInputStream extends InputStream {

    private static final int MAX_HEADER_LINE = 4_096;

    private final InputStream source;
    private final AwsSigV4.SigningContext signingContext;
    private final MessageDigest chunkDigest;
    private String previousSignature;
    private String declaredChunkSignature;
    private long chunkRemaining;
    private boolean finished;

    /**
     * @param source         the raw request body
     * @param signingContext verification context, or {@code null} to decode without
     *                       verifying chunk signatures
     */
    public AwsChunkedInputStream(InputStream source, AwsSigV4.SigningContext signingContext) {
        this.source = source;
        this.signingContext = signingContext;
        this.previousSignature = signingContext != null ? signingContext.seedSignature() : null;
        try {
            this.chunkDigest = signingContext != null ? MessageDigest.getInstance("SHA-256") : null;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    @Override
    public int read() throws IOException {
        byte[] one = new byte[1];
        int n = read(one, 0, 1);
        return n < 0 ? -1 : one[0] & 0xFF;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (finished) {
            return -1;
        }
        if (chunkRemaining == 0L && !advanceToNextChunk()) {
            return -1;
        }
        int toRead = (int) Math.min(len, chunkRemaining);
        int n = source.read(b, off, toRead);
        if (n < 0) {
            throw new EOFException("Unexpected end of aws-chunked body inside a chunk");
        }
        if (chunkDigest != null) {
            chunkDigest.update(b, off, n);
        }
        chunkRemaining -= n;
        if (chunkRemaining == 0L) {
            finishChunk();
        }
        return n;
    }

    /** Reads the next chunk header. Returns {@code false} when the final chunk was consumed. */
    private boolean advanceToNextChunk() throws IOException {
        String header = readLine();
        int semicolon = header.indexOf(';');
        String sizePart = semicolon < 0 ? header : header.substring(0, semicolon);
        long size;
        try {
            size = Long.parseLong(sizePart.trim(), 16);
        } catch (NumberFormatException e) {
            throw new IOException("Malformed aws-chunked size line: " + header);
        }
        declaredChunkSignature = extractSignature(header);
        if (size == 0L) {
            verifyChunkSignature(new byte[0]);
            readTrailers();
            finished = true;
            return false;
        }
        chunkRemaining = size;
        return true;
    }

    /** Called when the current chunk's data is fully consumed: verify and eat the CRLF. */
    private void finishChunk() throws IOException {
        if (chunkDigest != null) {
            verifyChunkSignatureDigest();
        }
        expectCrlf();
    }

    private void verifyChunkSignature(byte[] data) throws IOException {
        if (signingContext == null) {
            return;
        }
        chunkDigest.reset();
        chunkDigest.update(data);
        verifyChunkSignatureDigest();
    }

    private void verifyChunkSignatureDigest() throws IOException {
        if (signingContext == null) {
            return;
        }
        byte[] sha256 = chunkDigest.digest(); // also resets the digest for the next chunk
        String expected = AwsSigV4.chunkSignature(signingContext, previousSignature, sha256);
        if (declaredChunkSignature == null) {
            throw new IOException("Missing chunk-signature in a signed aws-chunked payload");
        }
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                declaredChunkSignature.getBytes(StandardCharsets.UTF_8))) {
            throw new IOException("aws-chunked chunk signature mismatch");
        }
        previousSignature = declaredChunkSignature;
    }

    /** Reads trailer headers after the final chunk, verifying the trailer signature if signed. */
    private void readTrailers() throws IOException {
        StringBuilder canonicalTrailers = new StringBuilder();
        String declaredTrailerSignature = null;
        while (true) {
            String line = readLine();
            if (line.isEmpty()) {
                break;
            }
            int colon = line.indexOf(':');
            if (colon < 0) {
                throw new IOException("Malformed aws-chunked trailer line: " + line);
            }
            String name = line.substring(0, colon).trim().toLowerCase();
            String value = line.substring(colon + 1).trim();
            if (name.equals("x-amz-trailer-signature")) {
                declaredTrailerSignature = value;
            } else {
                canonicalTrailers.append(name).append(':').append(value).append('\n');
            }
        }
        if (signingContext != null && declaredTrailerSignature != null) {
            byte[] trailerSha256;
            try {
                trailerSha256 = MessageDigest.getInstance("SHA-256")
                        .digest(canonicalTrailers.toString().getBytes(StandardCharsets.UTF_8));
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
            String expected = AwsSigV4.trailerSignature(signingContext, previousSignature, trailerSha256);
            if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                    declaredTrailerSignature.getBytes(StandardCharsets.UTF_8))) {
                throw new IOException("aws-chunked trailer signature mismatch");
            }
        }
    }

    private static String extractSignature(String header) {
        int marker = header.indexOf("chunk-signature=");
        if (marker < 0) {
            return null;
        }
        String sig = header.substring(marker + "chunk-signature=".length());
        int end = sig.indexOf(';');
        return (end < 0 ? sig : sig.substring(0, end)).trim();
    }

    /** Reads a CRLF-terminated line (without the terminator). Tolerates a leading CRLF-free start. */
    private String readLine() throws IOException {
        StringBuilder sb = new StringBuilder(64);
        while (true) {
            int c = source.read();
            if (c < 0) {
                throw new EOFException("Unexpected end of aws-chunked body in a header line");
            }
            if (c == '\r') {
                int lf = source.read();
                if (lf != '\n') {
                    throw new IOException("Malformed aws-chunked line terminator");
                }
                return sb.toString();
            }
            if (sb.length() >= MAX_HEADER_LINE) {
                throw new IOException("aws-chunked header line is too long");
            }
            sb.append((char) c);
        }
    }

    private void expectCrlf() throws IOException {
        int cr = source.read();
        int lf = source.read();
        if (cr != '\r' || lf != '\n') {
            throw new IOException("Missing CRLF after an aws-chunked chunk");
        }
    }

    @Override
    public void close() throws IOException {
        source.close();
    }
}
