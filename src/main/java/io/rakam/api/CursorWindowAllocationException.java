package io.rakam.api;

/**
 * This is Rakam's substitute for android.database.CursorWindowAllocationException.
 * Android's CursorWindow will throw that exception, but Android does not allow you to import
 * the exception class directly to catch it. This is Rakam's stand-in for that class.
 *
 * @hide
 */
public class CursorWindowAllocationException extends RuntimeException {
    public CursorWindowAllocationException(String description) {
        super(description);
    }
}
