package io.boins.core;

/**
 * Thrown when persistent structures are internally inconsistent
 * (e.g. an index record contradicts the free-cell registry, or a file has an invalid size).
 */
public class StorageCorruptedException extends BoinsException {

    public StorageCorruptedException(String message) {
        super(message);
    }

    public StorageCorruptedException(String message, Throwable cause) {
        super(message, cause);
    }
}
