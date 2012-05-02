package org.eclipse.jetty.io;

import java.nio.ByteBuffer;
import java.nio.channels.CompletionHandler;
import java.util.concurrent.Future;

/* ------------------------------------------------------------ */
/**Asynchronous End Point
 * <p>
 * This extension of EndPoint provides asynchronous scheduling methods.
 * The design of these has been influenced by NIO.2 Futures and Completion
 * handlers, but does not use those actual interfaces because: they have
 * some inefficiencies (eg buffers must be allocated before read); they have
 * unrequired overheads due to their generic nature (passing of attachments
 * and returning operation counts); there is no need to pass timeouts as 
 * {@link EndPoint#getMaxIdleTime() is used.
 * <p>
 * The intent of this API is that it can be used in either: a polling mode (like {@link Future})
 * ; in a callback mode (like {@link CompletionHandler} mode; or blocking mod;e or a hybrid mode
 * <h3>Blocking read</h3>
 * <pre>
 * endpoint.read().complete();
 * endpoint.fill(buffer);
 * </pre>
 * <h3>Polling read</h3>
 * <pre>
 * IOFuture read = endpoint.read();
 * ...
 * if (read.isReady())
 *   endpoint.fill(buffer);
 * </pre>
 * <h3>Callback read</h3>
 * <pre>
 * endpoint.read().setHandler(new IOCallback()
 * {
 *   public void onReady() { endpoint.fill(buffer); ... }
 *   public void onFail(IOException e) { ... }
 *   public void onTimeout() { ... }
 * }
 * </pre>
 * 
 * <h3>Blocking write</h3>
 * <pre>
 * endpoint.write(buffer).complete();
 * </pre>
 * <h3>Polling write</h3>
 * <pre>
 * IOFuture write = endpoint.write(buffer);
 * ...
 * if (write.isReady())
 *   // do next write
 * </pre>
 * <h3>Callback write</h3>
 * <pre>
 * endpoint.write(buffer).setHandler(new IOCallback()
 * {
 *   public void onReady() { ... }
 *   public void onFail(IOException e) { ... }
 *   public void onTimeout() { ... }
 * }
 * </pre>
 * <h3>Hybrid write</h3>
 * <pre>
 * IOFuture write = endpoint.write(buffer);
 * if (write.isReady())
 *   // write next
 * else
 *   write.setHandler(new IOCallback()
 *   {
 *     public void onReady() { ... }
 *     public void onFail(IOException e) { ... }
 *     public void onTimeout() { ... }
 *   }
 * </pre>
 * 
 * <h2>Compatibility Notes</h2>
 * Some Async IO APIs have the concept of setting read interest.  With this 
 * API calling {@link #read()} is equivalent to setting read interest to true 
 * and calling {@link IOFuture#cancel()} is equivalent to setting read interest 
 * to false. 
 */
public interface AsyncEndPoint extends EndPoint
{
    /* ------------------------------------------------------------ */
    AsyncConnection getAsyncConnection();

    /* ------------------------------------------------------------ */
    /** Schedule a read operation.
     * <p>
     * This method allows a {@link #fill(ByteBuffer)} operation to be scheduled 
     * with either blocking, polling or callback semantics.
     * @return an {@link IOFuture} instance that will be ready when a call to {@link #fill(ByteBuffer)} will 
     * return immediately with data without blocking. 
     * @throws IllegalStateException if another read operation has been scheduled and has not timedout, been cancelled or is ready.
     */
    IOFuture read() throws IllegalStateException;
    
    /* ------------------------------------------------------------ */
    /** Schedule a write operation.
     * This method performs {@link #flush(ByteBuffer...)} operations and allows the completion of 
     * the entire write to be scheduled with blocking, polling or callback semantics.
     * @param buffers One or more {@link ByteBuffer}s that will be flushed.
     * @return an {@link IOFuture} instance that will be ready when all the data in the buffers passed has been consumed by 
     * one or more calls to {@link #flush(ByteBuffer)}. 
     */
    IOFuture write(ByteBuffer... buffers) throws IllegalStateException;
    
    /* ------------------------------------------------------------ */
    /** Set if the endpoint should be checked for idleness
     */
    void setCheckForIdle(boolean check);

    /* ------------------------------------------------------------ */
    /** Get if the endpoint should be checked for idleness
     */
    boolean isCheckForIdle();

    /* ------------------------------------------------------------ */
    /**
     * @return Timestamp in ms since epoch of when the last data was
     * filled or flushed from this endpoint.
     */
    long getActivityTimestamp();

}
