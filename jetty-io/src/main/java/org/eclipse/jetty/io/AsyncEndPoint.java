package org.eclipse.jetty.io;

import java.nio.ByteBuffer;
import java.nio.channels.CompletionHandler;
import java.nio.channels.ReadPendingException;
import java.nio.channels.WritePendingException;
import java.util.concurrent.Future;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.ExecutorCallback;
import org.eclipse.jetty.util.FutureCallback;

/* ------------------------------------------------------------ */
/**Asynchronous End Point
 * <p>
 * This extension of EndPoint provides asynchronous scheduling methods.
 * The design of these has been influenced by NIO.2 Futures and Completion
 * handlers, but does not use those actual interfaces because: they have
 * some inefficiencies.
 * <p>
 * This class will frequently be used in conjunction with some of the utility
 * implementations of {@link Callback}, such as {@link FutureCallback} and 
 * {@link ExecutorCallback}. Examples are:
 * <h3>Blocking Read</h3>
 * A FutureCallback can be used to block until an endpoint is ready to be filled
 * from:
 * <blockquote><pre>
 * FutureCallback<String> future = new FutureCallback<>();
 * endpoint.readable("ContextObj",future);
 * ...
 * String context = future.get(); // This blocks
 * int filled=endpoint.fill(mybuffer);</pre></blockquote>
 * <h3>Dispatched Read</h3>
 * By using a different callback, the read can be done asynchronously in its own dispatched thread:
 * <blockquote><pre>
 * endpoint.readable("ContextObj",new ExecutorCallback<String>(executor)
 * {
 *   public void onCompleted(String context)
 *   {
 *     int filled=endpoint.fill(mybuffer);
 *     ...
 *   }
 *   public void onFailed(String context,Throwable cause) {...}
 * });</pre></blockquote>
 * The executor callback can also be customized to not dispatch in some circumstances when 
 * it knows it can use the callback thread and does not need to dispatch.
 * 
 * <h3>Blocking Write</h3>
 * The write contract is that the callback complete is not called until all data has been 
 * written or there is a failure.  For blocking this looks like:
 * 
 * <blockquote><pre>
 * FutureCallback<String> future = new FutureCallback<>();
 * endpoint.write("ContextObj",future,headerBuffer,contentBuffer);
 * String context = future.get(); // This blocks
 * </pre></blockquote>
 * 
 * <h3>Dispatched Write</h3>
 * Note also that multiple buffers may be passed in write so that gather writes 
 * can be done:
 * <blockquote><pre>
 * endpoint.write("ContextObj",new ExecutorCallback<String>(executor)
 * {
 *   public void onCompleted(String context)
 *   {
 *     int filled=endpoint.fill(mybuffer);
 *     ...
 *   }
 *   public void onFailed(String context,Throwable cause) {...}
 * },headerBuffer,contentBuffer);</pre></blockquote>
 * 
 */
public interface AsyncEndPoint extends EndPoint
{
    /* ------------------------------------------------------------ */
    /** Asynchronous a readable notification.
     * <p>
     * This method schedules a callback operations when a call to {@link #fill(ByteBuffer)} will return data or EOF.
     * @param context Context to return via the callback
     * @param callback The callback to call when an error occurs or we are readable.
     * @throws ReadPendingException if another read operation is concurrent.
     */
    <C> void readable(C context, Callback<C> callback) throws ReadPendingException;

    /* ------------------------------------------------------------ */
    /** Asynchronous write operation.
     * <p>
     * This method performs {@link #flush(ByteBuffer...)} operation(s) and do a callback when all the data 
     * has been flushed or an error occurs.
     * @param context Context to return via the callback
     * @param callback The callback to call when an error occurs or we are readable.
     * @param buffers One or more {@link ByteBuffer}s that will be flushed.
     * @throws WritePendingException if another write operation is concurrent.
     */
    <C> void write(C context, Callback<C> callback, ByteBuffer... buffers) throws WritePendingException;

    /* ------------------------------------------------------------ */
    /**
     * @return Timestamp in ms since epoch of when the last data was
     * filled or flushed from this endpoint.
     */
    long getIdleTimestamp();

    AsyncConnection getAsyncConnection();

    void setAsyncConnection(AsyncConnection connection);
    
    void onClose();
    
}
