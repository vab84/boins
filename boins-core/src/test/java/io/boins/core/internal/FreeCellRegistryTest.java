package io.boins.core.internal;

import io.boins.core.FreeCellsState;
import io.boins.core.StorageCorruptedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FreeCellRegistryTest {

    @TempDir
    Path tempDir;

    private Path file() {
        return tempDir.resolve("free-cells.boins");
    }

    @Test
    void putAndPopExactSize() throws Exception {
        try (FreeCellRegistry registry = FreeCellRegistry.open(file())) {
            registry.put(10L, 4_096L);
            OptionalLong popped = registry.pop(4_096L);
            assertTrue(popped.isPresent());
            assertEquals(10L, popped.getAsLong());
            assertTrue(registry.pop(4_096L).isEmpty());
        }
    }

    @Test
    void popPrefersSmallestFittingCell() throws Exception {
        try (FreeCellRegistry registry = FreeCellRegistry.open(file())) {
            registry.put(1L, 8_192L);
            registry.put(2L, 4_096L);
            registry.put(3L, 16_384L);
            assertEquals(2L, registry.pop(4_000L).orElseThrow());
        }
    }

    @Test
    void popRespectsTolerance() throws Exception {
        try (FreeCellRegistry registry = FreeCellRegistry.open(file())) {
            registry.put(1L, 1L << 30); // 1 GiB cell
            // A 1 KiB blob must not waste a 1 GiB cell.
            assertTrue(registry.pop(1_024L).isEmpty());
            // A blob nearly the same size is fine.
            assertTrue(registry.pop((1L << 30) - 1_000L).isPresent());
        }
    }

    @Test
    void toleranceFormulaIsMonotonic() {
        double small = FreeCellRegistry.maxWasteFraction(1_024L);
        double medium = FreeCellRegistry.maxWasteFraction(1L << 20);
        double large = FreeCellRegistry.maxWasteFraction(1L << 30);
        assertTrue(small > medium && medium > large,
                "larger cells must demand a tighter fit: " + small + " > " + medium + " > " + large);
        assertTrue(small < 1.0D && large > 0.0D);
    }

    @Test
    void fitsWithToleranceRejectsSmallerCells() {
        assertFalse(FreeCellRegistry.fitsWithTolerance(100L, 200L));
        assertTrue(FreeCellRegistry.fitsWithTolerance(200L, 200L));
        assertFalse(FreeCellRegistry.fitsWithTolerance(200L, 0L));
    }

    @Test
    void persistsAcrossReopen() throws Exception {
        try (FreeCellRegistry registry = FreeCellRegistry.open(file())) {
            registry.put(5L, 1_000L);
            registry.put(6L, 2_000L);
            registry.pop(2_000L); // consume id 6
        }
        try (FreeCellRegistry registry = FreeCellRegistry.open(file())) {
            FreeCellsState state = registry.state();
            assertEquals(1L, state.trackedCells());
            assertEquals(1L, state.tombstones());
            assertEquals(5L, registry.pop(1_000L).orElseThrow());
        }
    }

    @Test
    void tombstonedSlotsAreRecycled() throws Exception {
        try (FreeCellRegistry registry = FreeCellRegistry.open(file())) {
            registry.put(1L, 100L);
            registry.pop(100L);
            long sizeAfterPop = registry.state().fileSize();
            registry.put(2L, 200L); // must reuse the tombstoned slot
            assertEquals(sizeAfterPop, registry.state().fileSize());
        }
    }

    @Test
    void rePutUpdatesCellSize() throws Exception {
        try (FreeCellRegistry registry = FreeCellRegistry.open(file())) {
            registry.put(1L, 100L);
            registry.put(1L, 300L);
            assertEquals(1L, registry.state().trackedCells());
            assertTrue(registry.pop(250L).isPresent());
        }
    }

    @Test
    void removeDropsEntry() throws Exception {
        try (FreeCellRegistry registry = FreeCellRegistry.open(file())) {
            registry.put(1L, 100L);
            registry.remove(1L);
            registry.remove(42L); // unknown id is a no-op
            assertTrue(registry.pop(100L).isEmpty());
            assertEquals(0L, registry.state().trackedCells());
        }
    }

    @Test
    void invalidArgumentsRejected() throws Exception {
        try (FreeCellRegistry registry = FreeCellRegistry.open(file())) {
            assertThrows(IllegalArgumentException.class, () -> registry.put(-1L, 100L));
            assertThrows(IllegalArgumentException.class, () -> registry.put(1L, 0L));
            assertTrue(registry.pop(0L).isEmpty());
            assertTrue(registry.pop(-5L).isEmpty());
        }
    }

    @Test
    void corruptedFileSizeIsDetected() throws Exception {
        Files.write(file(), new byte[FreeCellRegistry.SLOT_SIZE + 1]);
        assertThrows(StorageCorruptedException.class, () -> FreeCellRegistry.open(file()));
    }
}
