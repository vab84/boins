package io.boins.core;

/**
 * Base checked exception for all Boins core operations.
 *
 * <p>Subclasses describe specific failure categories so callers can react precisely
 * (e.g. map to HTTP status codes in the server layer).</p>
 */
public class BoinsException extends Exception {

    public BoinsException(String message) {
        super(message);
    }

    public BoinsException(String message, Throwable cause) {
        super(message, cause);
    }
}
