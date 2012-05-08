package org.eclipse.jetty.io;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.util.Callback;

/* ------------------------------------------------------------ */
/* ------------------------------------------------------------ */
/* ------------------------------------------------------------ */
/** Async IO Future interface.
 * <p>
 * This interface make the future status of an IO operation available via
 * polling ({@link #isComplete()}, blocking ({@link #block()} or callback ({@link #setCallback(Callback, Object)}
 * <p>
 * This interface does not directly support a timeout for blocking. If an IO operation times out, then
 * this will be indicated via this interface by an {@link ExecutionException} containing a
 * {@link TimeoutException} (or similar).
 */
public interface IOFuture
{
    /* ------------------------------------------------------------ */
    /** Indicate if this Future is complete.
     * If this future has completed by becoming ready, excepting or timeout.
     * @return True if this future has completed by becoming ready or excepting.
     */
    boolean isDone();

    /* ------------------------------------------------------------ */
    /** Indicate the readyness of the IO system.
     * For input, ready means that there is data
     * ready to be consumed. For output ready means that the prior operation
     * has completed and another may be initiated.
     * @return True if the IO operation is ready.
     * @throws ExecutionException If an exception occurs during the IO operation
     */
    boolean isComplete() throws ExecutionException;

    /* ------------------------------------------------------------ */
    /** Cancel the IO operation.
     * @throws UnsupportedOperationException If the operation cannot be cancelled.
     */
    void cancel() throws UnsupportedOperationException;

    /* ------------------------------------------------------------ */
    /** Block until complete.
     * <p>This call blocks the calling thread until this AsyncIO is ready or
     * an exception.
     * @throws InterruptedException if interrupted while blocking
     * @throws ExecutionException If any exception occurs during the IO operation
     */
    void block() throws InterruptedException, ExecutionException;

    /* ------------------------------------------------------------ */
    /** Block until complete or timeout
     * <p>This call blocks the calling thread until this AsyncIO is ready or
     * an exception or a timeout.   In the case of the timeout, the IO operation is not affected
     * and can still continue to completion and this IOFuture is still usable.
     * @return true if the IOFuture completed or false if it timedout.
     * @throws InterruptedException if interrupted while blocking
     * @throws ExecutionException If any exception occurs during the IO operation
     */
    boolean block(long timeout, TimeUnit units) throws InterruptedException, ExecutionException;

    /* ------------------------------------------------------------ */
    /** Set an IOCallback.
     * Set an {@link Callback} instance to be called when the IO operation is ready or if
     * there is a failure or timeout.
     * @param callback
     */
    <C> void setCallback(Callback<C> callback, C context);
}
