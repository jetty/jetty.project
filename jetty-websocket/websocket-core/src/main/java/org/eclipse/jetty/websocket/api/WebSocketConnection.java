package org.eclipse.jetty.websocket.api;

import java.io.IOException;
import java.net.InetSocketAddress;

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
     * 
     * @throws IOException
     *             if unable to send the close frame, or close the connection successfully.
     * @see StatusCode
     * @see #close(int, String)
     */
    void close() throws IOException;

    /**
     * Terminate connection, with status code.
     * <p>
     * Advanced usage: results in an non-blocking async write, then connection close.
     * 
     * @param statusCode
     *            the status code
     * @param reason
     *            the (optional) reason. (can be null for no reason)
     * @throws IOException
     *             if unable to send the close frame, or close the connection successfully.
     * @see StatusCode
     */
    void close(int statusCode, String reason) throws IOException;

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
    InetSocketAddress getRemoteAddress();

    /**
     * Get the SubProtocol in use for this connection.
     * 
     * @return the negotiated sub protocol name in use for this connection, can be null if there is no sub-protocol negotiated.
     */
    String getSubProtocol();

    /**
     * Simple test to see if connection is open (and not closed)
     * 
     * @return true if connection still open
     */
    boolean isOpen();

    /**
     * Send a single ping messages.
     * <p>
     * NIO style with callbacks, allows for knowledge of successful ping send.
     * <p>
     * Use @OnWebSocketFrame and monitor Pong frames
     */
    <C> void ping(C context, Callback<C> callback, byte payload[]) throws IOException;

    /**
     * Send a a binary message.
     * <p>
     * NIO style with callbacks, allows for concurrent results of the write operation.
     */
    <C> void write(C context, Callback<C> callback, byte buf[], int offset, int len) throws IOException;

    /**
     * Send a series of text messages.
     * <p>
     * NIO style with callbacks, allows for concurrent results of the entire write operation. (Callback is only called once at the end of processing all of the
     * messages)
     */
    <C> void write(C context, Callback<C> callback, String... messages) throws IOException;
}