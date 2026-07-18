package io.boins.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoinsOptionsFileTest {

    @TempDir
    Path tempDir;

    @Test
    void missingFileIsCreatedWithParentsAndParsesBack() throws Exception {
        Path file = tempDir.resolve("deep/nested/dirs/boins-options.yaml");
        BoinsOptions options = BoinsOptionsFile.loadOrCreate(file);
        assertTrue(Files.exists(file), "default options file must be created");
        assertEquals(36, options.blobFileLimit());
        assertEquals(BoinsOptions.FsyncMode.INTERVAL, options.fsyncMode());
        assertEquals(1, options.repositories().size());
        // ./repo1 resolves against the file's directory
        assertEquals(file.toAbsolutePath().getParent().resolve("repo1").normalize(),
                options.repositories().getFirst().dir());
    }

    @Test
    void openByPathWorksEndToEnd() throws Exception {
        Path file = tempDir.resolve("storage/options.yaml");
        // shrink limits so the storage opens on a small test disk
        BoinsOptionsFile.loadOrCreate(file); // create default first
        Files.writeString(file, """
                blobFileLimit: 20
                minRepositoryFreeBytes: 0
                fsyncMode: NEVER
                repositories:
                  - dir: ./repo1
                    blobIdOffset: 0
                    diskType: SSD
                """);
        try (Boins boins = Boins.open(file)) {
            byte[] data = "hello from yaml".getBytes();
            WriteResult w = boins.write(data, BlobMetadata.ofKey("k"));
            try (var in = boins.read(w.blobId())) {
                assertArrayEquals(data, in.readAllBytes());
            }
        }
        assertTrue(Files.isDirectory(file.getParent().resolve("repo1")));
    }

    @Test
    void allKeysAreParsed() throws Exception {
        Path file = tempDir.resolve("full.yaml");
        Path absoluteMetrics = tempDir.resolve("abs-metrics.boins").toAbsolutePath();
        Files.writeString(file, """
                # full configuration
                blobFileLimit: 24
                minBlobBytes: 2048
                fsyncMode: always     # case-insensitive
                fsyncIntervalMillis: 500
                minRepositoryFreeBytes: 12345
                metricsFlushIntervalMillis: 7000
                freeCellsFile: ./cells.boins
                metricsFile: %s
                repositories:
                  - dir: ./a
                    blobIdOffset: 0
                    diskType: HDD
                  - dir: ./b
                    blobIdOffset: 1000000
                """.formatted(absoluteMetrics));
        BoinsOptions options = BoinsOptionsFile.loadOrCreate(file);
        assertEquals(24, options.blobFileLimit());
        assertEquals(2048L, options.minBlobBytes());
        assertEquals(BoinsOptions.FsyncMode.ALWAYS, options.fsyncMode());
        assertEquals(500L, options.fsyncIntervalMillis());
        assertEquals(12345L, options.minRepositoryFreeBytes());
        assertEquals(7000L, options.metricsFlushIntervalMillis());
        assertEquals(tempDir.resolve("cells.boins").toAbsolutePath().normalize(),
                options.freeCellsFile().toAbsolutePath().normalize());
        assertEquals(absoluteMetrics, options.metricsFile());
        assertEquals(2, options.repositories().size());
        assertEquals(BoinsOptions.DiskType.HDD, options.repositories().get(0).diskType());
        assertEquals(1_000_000L, options.repositories().get(1).blobIdOffset());
        assertEquals(BoinsOptions.DiskType.SSD, options.repositories().get(1).diskType(), "diskType defaults to SSD");
    }

    @Test
    void unknownKeyIsRejected() throws Exception {
        Path file = write("blobFileLimit: 30\nwhatIsThis: 1\nrepositories:\n  - dir: ./r\n");
        BoinsException e = assertThrows(BoinsException.class, () -> BoinsOptionsFile.loadOrCreate(file));
        assertTrue(e.getMessage().contains("whatIsThis"), e.getMessage());
        assertTrue(e.getMessage().contains("line 2"), e.getMessage());
    }

    @Test
    void unknownRepositoryKeyIsRejected() throws Exception {
        Path file = write("repositories:\n  - dir: ./r\n    speed: fast\n");
        BoinsException e = assertThrows(BoinsException.class, () -> BoinsOptionsFile.loadOrCreate(file));
        assertTrue(e.getMessage().contains("speed"), e.getMessage());
    }

    @Test
    void malformedLineIsRejected() throws Exception {
        Path file = write("this is not yaml at all\n");
        assertThrows(BoinsException.class, () -> BoinsOptionsFile.loadOrCreate(file));
    }

    @Test
    void badNumberIsRejected() throws Exception {
        Path file = write("blobFileLimit: many\nrepositories:\n  - dir: ./r\n");
        BoinsException e = assertThrows(BoinsException.class, () -> BoinsOptionsFile.loadOrCreate(file));
        assertTrue(e.getMessage().contains("blobFileLimit"), e.getMessage());
    }

    @Test
    void badEnumIsRejected() throws Exception {
        Path file = write("fsyncMode: SOMETIMES\nrepositories:\n  - dir: ./r\n");
        BoinsException e = assertThrows(BoinsException.class, () -> BoinsOptionsFile.loadOrCreate(file));
        assertTrue(e.getMessage().contains("SOMETIMES"), e.getMessage());
    }

    @Test
    void missingRepositoriesIsRejected() throws Exception {
        Path file = write("blobFileLimit: 30\n");
        BoinsException e = assertThrows(BoinsException.class, () -> BoinsOptionsFile.loadOrCreate(file));
        assertTrue(e.getMessage().contains("repositories"), e.getMessage());
    }

    @Test
    void repositoryWithoutDirIsRejected() throws Exception {
        Path file = write("repositories:\n  - blobIdOffset: 5\n");
        BoinsException e = assertThrows(BoinsException.class, () -> BoinsOptionsFile.loadOrCreate(file));
        assertTrue(e.getMessage().contains("dir"), e.getMessage());
    }

    @Test
    void outOfRangeValueIsRejectedWithFileContext() throws Exception {
        Path file = write("blobFileLimit: 99\nrepositories:\n  - dir: ./r\n");
        BoinsException e = assertThrows(BoinsException.class, () -> BoinsOptionsFile.loadOrCreate(file));
        assertTrue(e.getMessage().contains(file.toString()), e.getMessage());
    }

    @Test
    void quotedValuesAndCommentsAreHandled() throws Exception {
        Path file = write("""
                fsyncMode: "NEVER"        # quoted value
                repositories:
                  - dir: './my repo'      # path with a space
                """);
        BoinsOptions options = BoinsOptionsFile.loadOrCreate(file);
        assertEquals(BoinsOptions.FsyncMode.NEVER, options.fsyncMode());
        assertTrue(options.repositories().getFirst().dir().toString().endsWith("my repo"));
    }

    private Path write(String content) throws Exception {
        Path file = tempDir.resolve("options-" + content.hashCode() + ".yaml");
        Files.writeString(file, content);
        return file;
    }
}
