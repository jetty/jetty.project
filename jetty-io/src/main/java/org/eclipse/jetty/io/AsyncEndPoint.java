package org.eclipse.jetty.io;

import java.nio.ByteBuffer;
import java.nio.channels.CompletionHandler;
import java.nio.channels.ReadPendingException;
import java.nio.channels.WritePendingException;
import java.util.concurrent.Future;

import org.eclipse.jetty.util.Callback;

/* ------------------------------------------------------------ */
/**Asynchronous End Point
 * <p>
 * This extension of EndPoint provides asynchronous scheduling methods.
 * The design of these has been influenced by NIO.2 Futures and Completion
 * handlers, but does not use those actual interfaces because: they have
 * some inefficiencies.
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
    long getIdleTimestamp();

    AsyncConnection getAsyncConnection();

    void setAsyncConnection(AsyncConnection connection);
    
    void onClose();
    
}
