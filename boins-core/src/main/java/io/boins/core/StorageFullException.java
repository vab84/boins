package io.boins.core;

/**
 * Thrown when no repository has enough free space to accept a new blob.
 */
public class StorageFullException extends BoinsException {

    public StorageFullException(String message) {
        super(message);
    }
}
