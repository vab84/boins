package io.boins.core;

import java.util.List;

/**
 * Snapshot of the storage state, used by monitoring and the admin interface.
 */
public record BoinsState(List<RepositoryState> repositories, FreeCellsState freeCells) {

    public BoinsState {
        repositories = List.copyOf(repositories);
    }
}
