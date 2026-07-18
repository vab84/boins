package io.boins.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerConfigFileTest {

    @TempDir
    Path tempDir;

    @Test
    void missingConfigIsCreatedWithCommentedDefaults() throws Exception {
        Path file = tempDir.resolve("deep/nested/boins.yaml");
        BoinsServerConfig config = BoinsServerConfig.loadOrCreate(file);
        assertTrue(Files.exists(file), "default config file must be created with parent dirs");

        // Defaults parse back and pass validation.
        assertEquals("0.0.0.0", config.host);
        assertEquals(9000, config.port);
        assertTrue(config.virtualThreads);
        assertTrue(config.buckets.isEmpty(), "a first run must not define credentials");
        assertEquals(1, config.storage.repositories.size());
        assertEquals("./data/repo1", config.storage.repositories.getFirst().dir);

        // Every option is explained: the template must carry comments.
        String text = Files.readString(file);
        assertTrue(text.contains("# ALWAYS | INTERVAL | NEVER"));
        assertTrue(text.contains("NEVER change it later"));
        assertTrue(text.contains("# buckets:"), "a commented bucket example must be present");
    }

    @Test
    void existingConfigIsNotOverwritten() throws Exception {
        Path file = tempDir.resolve("boins.yaml");
        String custom = """
                port: 9500
                storage:
                  repositories:
                    - dir: ./custom-repo
                """;
        Files.writeString(file, custom);
        BoinsServerConfig config = BoinsServerConfig.loadOrCreate(file);
        assertEquals(9500, config.port);
        assertEquals(custom, Files.readString(file), "an existing file must stay byte-identical");
    }

    @Test
    void invalidConfigThrowsInsteadOfBeingReplaced() throws Exception {
        Path file = tempDir.resolve("broken.yaml");
        Files.writeString(file, "port: [not, a, number\n");
        assertThrows(IOException.class, () -> BoinsServerConfig.loadOrCreate(file));
        assertEquals("port: [not, a, number\n", Files.readString(file), "broken files are never replaced");
    }
}
