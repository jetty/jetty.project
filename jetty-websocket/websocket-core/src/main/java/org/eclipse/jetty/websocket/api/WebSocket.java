package org.eclipse.jetty.websocket.api;

import java.io.IOException;
import java.nio.ByteBuffer;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.frames.BaseFrame;
import org.eclipse.jetty.websocket.frames.BinaryFrame;
import org.eclipse.jetty.websocket.frames.TextFrame;

/**
 * Constants for WebSocket protocol as-defined in <a href="https://tools.ietf.org/html/rfc6455">RFC-6455</a>.
 * <p>
 * NOTE: Proposed interface for API (not yet settled)
 */
public interface WebSocket
{
    /**
     * Server side interface (to be moved once API settles down)
     */
    public static interface Accept
    {
        WebSocket acceptWebSocket(WebSocketHandshakeRequest request, WebSocketHandshakeResponse response);
    }

    /**
     * Advanced usage, for those interested in flags
     */
    public static interface BinaryFrameListener
    {
        void onWebSocketBinary(BinaryFrame frame);
    }

    /**
     * Basic usage.
     */
    public static interface BinaryListener
    {
        void onWebSocketBinary(byte buf[], int offset, int len);
    }

    /**
     * NIO flavored basic usage.
     */
    public static interface ByteBufferListener
    {
        void onWebSocketBinary(ByteBuffer... buffer);
    }

    public static interface Connection
    {
        /**
         * Terminate connection, normally, without a reason.
         * <p>
         * Issues a CloseFrame via {@link #write(Object, Callback, BaseFrame...)}
         * 
         * @TODO: determine contract for dealing with pending incoming frames.
         */
        void close();

        /**
         * Terminate connection, with status code.
         * <p>
         * Issues a CloseFrame via {@link #write(Object, Callback, BaseFrame...)}
         * 
         * @param statusCode
         *            the status code
         * @param reason
         *            the (optional) reason. (can be null for no reason)
         * @TODO: determine contract for dealing with pending incoming frames.
         */
        void close(int statusCode, String reason);

        String getSubProtocol();

        boolean isOpen();

        /**
         * Basic usage, results in non-blocking call to {@link #write(Object, Callback, ByteBuffer...)}
         */
        void sendBinary(byte[] data, int offset, int length) throws IOException;

        /**
         * Basic usage, results in non-blocking call to {@link #write(Object, Callback, ByteBuffer...)}
         */
        void sendBinary(ByteBuffer buffer) throws IOException;

        /**
         * Basic usage, results in non-blocking call to {@link #write(Object, Callback, ByteBuffer...)}
         */
        void sendBinary(ByteBuffer... buffers) throws IOException;

        /**
         * Basic usage, results in non-blocking call to {@link #write(Object, Callback, String...)}
         */
        void sendText(String message) throws IOException;

        /**
         * Advanced usage, with callbacks, enters into outgoing queue
         */
        <C> void write(C context, Callback<C> callback, BaseFrame... frames) throws IOException;

        /**
         * Advanced usage, with callbacks, enters into outgoing queue
         */
        <C> void write(C context, Callback<C> callback, ByteBuffer... buffers) throws IOException;

        /**
         * Advanced usage, with callbacks, enters into outgoing queue
         */
        <C> void write(C context, Callback<C> callback, String... messages) throws IOException;
    }

    /**
     * Advanced usage, text frame access to flags as well
     */
    public static interface TextFrameListener
    {
        void onWebSocketTextFrame(TextFrame frame);
    }

    /**
     * Basic usage
     */
    public static interface TextListener
    {
        void onWebSocketText(String message);
    }

    /**
     * Per <a href="https://tools.ietf.org/html/rfc6455#section-1.3">RFC 6455, section 1.3</a> - Opening Handshake - this version is "13"
     */
    public final static int VERSION = 13;

    void onClose(int statusCode, String reason);

    void onConnect(WebSocket.Connection connection);

    /**
     * Annotation Ideas (Various WebSocket Events):
     * 
     * <pre>
     * @OnWebSocketHandshake (server side only?)
     * public void {methodname}(WebSocket.Connection conn)
     * 
     * @OnWebSocketConnect
     * public void {methodname}(WebSocket.Connection conn)
     * 
     * @OnWebSocketDisconnect
     * public void {methodname}(WebSocket.Connection conn)
     * 
     * @OnWebSocketFrame(type=CloseFrame.class)
     * public void {methodname}(CloseFrame frame);
     * public void {methodname}(WebSocket.Connection conn, CloseFrame frame);
     * 
     * @OnWebSocketText
     * public void {methodname}(String text);
     * public void {methodname}(WebSocket.Connection conn, String text);
     * 
     * @OnWebSocketBinary
     * public void {methodname}(byte buf[], int offset, int length);
     * public void {methodname}(WebSocket.Connection conn, byte buf[], int offset, int length);
     * public void {methodname}(ByteBuffer buffer);
     * public void {methodname}(WebSocket.Connection conn, ByteBuffer buffer);
     * 
     * @OnWebSocketClose
     * public void {methodnamne}(int statusCode, String reason);
     * public void {methodnamne}(WebSocket.Connection conn, int statusCode, String reason);
     * </pre>
     */
}
