package org.eclipse.jetty.websocket.api;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;

import org.eclipse.jetty.util.Callback;

/**
 * Connection interface for WebSocket protocol <a href="https://tools.ietf.org/html/rfc6455">RFC-6455</a>.
 */
public interface WebSocketConnection
{
    /**
     * Terminate connection, {@link StatusCode#NORMAL}, without a reason.
     * <p>
     * Basic usage: results in an non-blocking async write, then connection close.
     */
    void close();

    /**
     * Terminate connection, with status code.
     * <p>
     * Advanced usage: results in an non-blocking async write, then connection close.
     * 
     * @param statusCode
     *            the status code
     * @param reason
     *            the (optional) reason. (can be null for no reason)
     * @see StatusCode
     */
    void close(int statusCode, String reason);

    /**
     * Access the (now read-only) {@link WebSocketPolicy} in use for this connection.
     * 
     * @return the policy in use
     */
    WebSocketPolicy getPolicy();

    /**
     * Get the remote Address in use for this connection.
     * 
     * @return the remote address if available. (situations like mux extension and proxying makes this information unreliable)
     */
    InetAddress getRemoteAddress();

    /**
     * Get the SubProtocol in use for this connection.
     * 
     * @return the negotiated sub protocol name in use for this connection.
     */
    String getSubProtocol();

    /**
     * Simple test to see if connection is open (and not closed)
     * 
     * @return true if connection still open
     */
    boolean isOpen();

    /**
     * Send a binary message.
     * <p>
     * Basic usage, results in an non-blocking async write.
     */
    void write(byte[] data, int offset, int length) throws IOException;

    /**
     * Send a series of binary messages.
     * <p>
     * Note: each buffer results in its own binary message frame.
     * <p>
     * Basic usage, results in a series of non-blocking async writes.
     */
    void write(ByteBuffer... buffers) throws IOException;

    /**
     * Send a series of binary messages.
     * <p>
     * Note: each buffer results in its own binary message frame.
     * <p>
     * Advanced usage, with callbacks, allows for concurrent NIO style results of the entire write operation. (Callback is only called once at the end of
     * processing all of the buffers)
     */
    <C> void write(C context, Callback<C> callback, ByteBuffer... buffers) throws IOException;

    /**
     * Send a series of text messages.
     * <p>
     * Note: each messages results in its own text message frame.
     * <p>
     * Advanced usage, with callbacks, allows for concurrent NIO style results of the entire write operation. (Callback is only called once at the end of
     * processing all of the buffers)
     */
    <C> void write(C context, Callback<C> callback, String... messages) throws IOException;

    /**
     * Send a text message.
     * <p>
     * Basic usage, results in an non-blocking async write.
     */
    void write(String message) throws IOException;
}