//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.websocket.api;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;

import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;

/**
 * Session represents an active link of communications with a Remote WebSocket Endpoint.
 */
public interface Session extends Closeable
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
     * @param closeStatus
     *            the reason for the closure
     * 
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
     * @param statusCode
     *            the status code
     * @param reason
     *            the (optional) reason. (can be null for no reason)
     * @see StatusCode
     * 
     * @see #close()
     * @see #close(CloseStatus)
     * @see #disconnect()
     */
    void close(int statusCode, String reason);

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
     * @throws IOException
     *             if unable to disconnect
     * 
     * @see #close()
     * @see #close(CloseStatus)
     * @see #close(int, String)
     * @see #disconnect()
     */
    void disconnect() throws IOException;

    /**
     * Return the number of milliseconds before this conversation will be closed by the container if it is inactive, ie no messages are either sent or received
     * in that time.
     * 
     * @return the timeout in milliseconds.
     */
    long getIdleTimeout();

    /**
     * Get the address of the local side.
     * 
     * @return the local side address
     */
    public InetSocketAddress getLocalAddress();

    /**
     * Access the (now read-only) {@link WebSocketPolicy} in use for this connection.
     * 
     * @return the policy in use
     */
    WebSocketPolicy getPolicy();

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
     * Get the address of the remote side.
     * 
     * @return the remote side address
     */
    public InetSocketAddress getRemoteAddress();

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
    abstract boolean isOpen();

    /**
     * Return true if and only if the underlying socket is using a secure transport.
     * 
     * @return whether its using a secure transport
     */
    boolean isSecure();

    /**
     * Set the number of milliseconds before this conversation will be closed by the container if it is inactive, ie no messages are either sent or received.
     * 
     * @param ms
     *            the number of milliseconds.
     */
    void setIdleTimeout(long ms);

    /**
     * Suspend a the incoming read events on the connection.
     * 
     * @return the suspend token suitable for resuming the reading of data on the connection.
     */
    SuspendToken suspend();
}
