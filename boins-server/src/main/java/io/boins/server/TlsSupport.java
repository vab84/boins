package io.boins.server;

import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.ssl.SslContextFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TLS wiring without extra dependencies: loads a PEM certificate chain and a PKCS#8
 * private key (the format Let's Encrypt and openssl produce) into an in-memory keystore
 * and builds a Jetty HTTPS connector from it.
 *
 * <p>PKCS#1 keys ({@code BEGIN RSA PRIVATE KEY}) are not supported — convert once with
 * {@code openssl pkcs8 -topk8 -nocrypt}.</p>
 */
public final class TlsSupport {

    private static final Pattern PEM_BLOCK =
            Pattern.compile("-----BEGIN ([A-Z0-9 ]+)-----([^-]+)-----END \\1-----", Pattern.DOTALL);

    private TlsSupport() {
    }

    /** Builds an HTTPS connector serving {@code host:port} with the given PEM material. */
    public static ServerConnector httpsConnector(Server server, String host, int port,
                                                 Path certificatePath, Path privateKeyPath)
            throws IOException, GeneralSecurityException {
        SslContextFactory.Server ssl = new SslContextFactory.Server();
        char[] password = "boins-in-memory".toCharArray();
        ssl.setKeyStore(keyStoreFromPem(certificatePath, privateKeyPath, password));
        ssl.setKeyStorePassword(new String(password));

        HttpConfiguration https = new HttpConfiguration();
        https.addCustomizer(new SecureRequestCustomizer());
        ServerConnector connector = new ServerConnector(server, ssl, new HttpConnectionFactory(https));
        connector.setHost(host);
        connector.setPort(port);
        return connector;
    }

    /** Loads PEM cert chain + PKCS#8 key into an in-memory PKCS12 keystore. */
    public static KeyStore keyStoreFromPem(Path certificatePath, Path privateKeyPath, char[] password)
            throws IOException, GeneralSecurityException {
        List<Certificate> chain = readCertificates(certificatePath);
        if (chain.isEmpty()) {
            throw new IOException("No certificates found in " + certificatePath);
        }
        PrivateKey key = readPrivateKey(privateKeyPath);
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, null);
        keyStore.setKeyEntry("boins", key, password, chain.toArray(Certificate[]::new));
        return keyStore;
    }

    static List<Certificate> readCertificates(Path pemFile) throws IOException, GeneralSecurityException {
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        List<Certificate> chain = new ArrayList<>();
        for (PemBlock block : readPemBlocks(pemFile)) {
            if (block.type().equals("CERTIFICATE")) {
                chain.add(factory.generateCertificate(new java.io.ByteArrayInputStream(block.der())));
            }
        }
        return chain;
    }

    static PrivateKey readPrivateKey(Path pemFile) throws IOException, GeneralSecurityException {
        for (PemBlock block : readPemBlocks(pemFile)) {
            switch (block.type()) {
                case "PRIVATE KEY" -> {
                    PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(block.der());
                    for (String algorithm : new String[]{"RSA", "EC", "Ed25519"}) {
                        try {
                            return KeyFactory.getInstance(algorithm).generatePrivate(spec);
                        } catch (InvalidKeySpecException ignored) {
                            // try the next algorithm
                        }
                    }
                    throw new InvalidKeySpecException(
                            "Unsupported private key algorithm in " + pemFile + " (tried RSA, EC, Ed25519)");
                }
                case "RSA PRIVATE KEY", "EC PRIVATE KEY" -> throw new InvalidKeySpecException(
                        "PKCS#1/SEC1 private keys are not supported. Convert to PKCS#8: "
                                + "openssl pkcs8 -topk8 -nocrypt -in key.pem -out key-pkcs8.pem");
                default -> {
                    // not a key block; keep scanning
                }
            }
        }
        throw new IOException("No PRIVATE KEY block found in " + pemFile);
    }

    private record PemBlock(String type, byte[] der) {
    }

    private static List<PemBlock> readPemBlocks(Path pemFile) throws IOException {
        String text = Files.readString(pemFile, StandardCharsets.US_ASCII);
        List<PemBlock> blocks = new ArrayList<>();
        Matcher matcher = PEM_BLOCK.matcher(text);
        while (matcher.find()) {
            String base64 = matcher.group(2).replaceAll("\\s", "");
            blocks.add(new PemBlock(matcher.group(1), Base64.getDecoder().decode(base64)));
        }
        return blocks;
    }
}
