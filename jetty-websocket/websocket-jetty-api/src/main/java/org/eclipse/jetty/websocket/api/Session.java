//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.api;

import java.io.Closeable;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;

/**
 * Session represents an active link of communications with a Remote WebSocket Endpoint.
 */
public interface Session extends WebSocketPolicy, Closeable
{
    /**
     * Request a close of the current conversation with a normal status code and no reason phrase.
     * <p>
     * This will enqueue a graceful close to the remote endpoint.
     *
     * @see #close(CloseStatus)
     * @see #close(int, String)
     * @see #disconnect()
     */
    @Override
    void close();

    /**
     * Request Close the current conversation, giving a reason for the closure. Note the websocket spec defines the acceptable uses of status codes and reason
     * phrases.
     * <p>
     * This will enqueue a graceful close to the remote endpoint.
     *
     * @param closeStatus the reason for the closure
     * @see #close()
     * @see #close(int, String)
     * @see #disconnect()
     */
    void close(CloseStatus closeStatus);

    /**
     * Send a websocket Close frame, with status code.
     * <p>
     * This will enqueue a graceful close to the remote endpoint.
     *
     * @param statusCode the status code
     * @param reason the (optional) reason. (can be null for no reason)
     * @see StatusCode
     * @see #close()
     * @see #close(CloseStatus)
     * @see #disconnect()
     */
    void close(int statusCode, String reason);

    /**
     * Send a websocket Close frame, with status code.
     * <p>
     * This will enqueue a graceful close to the remote endpoint.
     *
     * @param statusCode the status code
     * @param reason the (optional) reason. (can be null for no reason)
     * @param callback the callback to track close frame sent (or failed)
     * @see StatusCode
     * @see #close()
     * @see #close(CloseStatus)
     * @see #disconnect()
     */
    default void close(int statusCode, String reason, WriteCallback callback)
    {
        try
        {
            close(statusCode, reason);
            callback.writeSuccess();
        }
        catch (Throwable t)
        {
            callback.writeFailed(t);
        }
    }

    /**
     * Issue a harsh disconnect of the underlying connection.
     * <p>
     * This will terminate the connection, without sending a websocket close frame.
     * <p>
     * Once called, any read/write activity on the websocket from this point will be indeterminate.
     * <p>
     * Once the underlying connection has been determined to be closed, the various onClose() events (either
     * {@link WebSocketListener#onWebSocketClose(int, String)} or {@link OnWebSocketClose}) will be called on your
     * websocket.
     *
     * @see #close()
     * @see #close(CloseStatus)
     * @see #close(int, String)
     */
    void disconnect();

    /**
     * The Local Socket Address for the active Session
     * <p>
     * Do not assume that this will return a {@link InetSocketAddress} in all cases.
     * Use of various proxies, and even UnixSockets can result a SocketAddress being returned
     * without supporting {@link InetSocketAddress}
     * </p>
     *
     * @return the SocketAddress for the local connection, or null if not supported by Session
     */
    SocketAddress getLocalAddress();

    /**
     * Access the (now read-only) {@link WebSocketPolicy} in use for this connection.
     *
     * @return the policy in use
     */
    default WebSocketPolicy getPolicy()
    {
        return this;
    }

    /**
     * Returns the version of the websocket protocol currently being used. This is taken as the value of the Sec-WebSocket-Version header used in the opening
     * handshake. i.e. "13".
     *
     * @return the protocol version
     */
    String getProtocolVersion();

    /**
     * Return a reference to the RemoteEndpoint object representing the other end of this conversation.
     *
     * @return the remote endpoint
     */
    RemoteEndpoint getRemote();

    /**
     * The Remote Socket Address for the active Session
     * <p>
     * Do not assume that this will return a {@link InetSocketAddress} in all cases.
     * Use of various proxies, and even UnixSockets can result a SocketAddress being returned
     * without supporting {@link InetSocketAddress}
     * </p>
     *
     * @return the SocketAddress for the remote connection, or null if not supported by Session
     */
    SocketAddress getRemoteAddress();

    /**
     * Get the UpgradeRequest used to create this session
     *
     * @return the UpgradeRequest used to create this session
     */
    UpgradeRequest getUpgradeRequest();

    /**
     * Get the UpgradeResponse used to create this session
     *
     * @return the UpgradeResponse used to create this session
     */
    UpgradeResponse getUpgradeResponse();

    /**
     * Return true if and only if the underlying socket is open.
     *
     * @return whether the session is open
     */
    boolean isOpen();

    /**
     * Return true if and only if the underlying socket is using a secure transport.
     *
     * @return whether its using a secure transport
     */
    boolean isSecure();

    /**
     * Suspend the delivery of incoming WebSocket frames.
     * <p>
     * If this is called from inside the scope of the message handler the suspend takes effect immediately.
     * If suspend is called outside the scope of the message handler then the call may take effect
     * after 1 more frame is delivered.
     * </p>
     *
     * @return the suspend token suitable for resuming the reading of data on the connection.
     */
    SuspendToken suspend();
}
