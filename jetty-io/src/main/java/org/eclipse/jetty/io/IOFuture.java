package org.eclipse.jetty.io;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/* ------------------------------------------------------------ */
/* ------------------------------------------------------------ */
/* ------------------------------------------------------------ */
/** Async IO Future interface.
 * <p>
 * This interface make the future status of an IO operation available via 
 * polling ({@link #isReady()}, blocking ({@link #await()} or callback ({@link #setCallback(Callback)}
 * 
 */
public interface IOFuture
{
    /* ------------------------------------------------------------ */
    /** Indicate if this Future is complete.
     * If this future has completed by becoming ready, excepting or timeout.   
     * @return True if this future has completed by becoming ready, excepting or timeout.
     */
    boolean isComplete();
    
    /* ------------------------------------------------------------ */
    /** Indicate the readyness of the IO system.
     * For input, ready means that there is data
     * ready to be consumed. For output ready means that the prior operation 
     * has completed and another may be initiated.   
     * @return True if the IO operation is ready.
     * @throws ExecutionException If an exception occurs during the IO operation
     */
    boolean isReady() throws ExecutionException;
    
    /* ------------------------------------------------------------ */
    /** Cancel the IO operation.
     * @throws UnsupportedOperationException If the operation cannot be cancelled.
     */
    void cancel() throws UnsupportedOperationException;
    
    /* ------------------------------------------------------------ */
    /** Wait until complete.
     * <p>This call blocks the calling thread until this AsyncIO is ready or
     * an exception or until a timeout due to {@link EndPoint#getMaxIdleTime()}.
     * @throws InterruptedException if interrupted while blocking
     * @throws ExecutionException If any exception occurs during the IO operation
     */
    void await() throws InterruptedException, ExecutionException;
    
    /* ------------------------------------------------------------ */
    /** Set an IOCallback.
     * Set an {@link Callback} instance to be called when the IO operation is ready or if
     * there is a failure or timeout.
     * @param callback
     */
    void setCallback(Callback callback);
    
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    interface Callback
    {
        void onReady();
        void onFail(Throwable cause);
    }
}
