package io.boins.server;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Standalone entry point: {@code boins-server [config.yaml]}.
 *
 * <p>Loads the YAML configuration (default {@code ./boins.yaml}) and serves until
 * terminated. When the configuration file does not exist, a commented default file is
 * created at that path (parent directories included) and the server starts with it —
 * without buckets, so the first run is safe: add credentials and restart.</p>
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        Path configPath = Path.of(args.length > 0 ? args[0] : "./boins.yaml");
        boolean existed = Files.exists(configPath);
        BoinsServerConfig config = BoinsServerConfig.loadOrCreate(configPath);
        if (!existed) {
            System.out.println("Created a default configuration: " + configPath.toAbsolutePath());
            System.out.println("It defines no buckets yet — fill in the 'buckets' section and restart.");
        }
        BoinsServer server = BoinsServer.start(config);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                server.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, "boins-shutdown"));
        System.out.println("Boins server listening on " + (config.ssl.enabled ? "https" : "http")
                + "://" + config.host + ":" + server.port());
    }
}
