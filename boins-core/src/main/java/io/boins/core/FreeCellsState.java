package io.boins.core;

/**
 * Snapshot of the free-cell registry (cells of deleted blobs available for reuse).
 *
 * @param trackedCells   number of cells currently available for reuse
 * @param tombstones     number of consumed slots in the registry file awaiting reuse
 * @param minCellSize    smallest tracked cell size, or {@code -1} when empty
 * @param maxCellSize    largest tracked cell size, or {@code -1} when empty
 * @param fileSize       registry file size in bytes
 */
public record FreeCellsState(
        long trackedCells,
        long tombstones,
        long minCellSize,
        long maxCellSize,
        long fileSize) {
}
