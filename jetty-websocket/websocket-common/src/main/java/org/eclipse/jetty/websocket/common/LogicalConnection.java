//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.websocket.common;

import java.net.InetSocketAddress;
import java.util.concurrent.Executor;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.api.SuspendToken;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.api.extensions.IncomingFrames;
import org.eclipse.jetty.websocket.api.extensions.OutgoingFrames;

public interface LogicalConnection extends OutgoingFrames, SuspendToken
{
    /**
     * Test if Connection State allows for reading of frames.
     *
     * @return true if able to read, false otherwise.
     */
    boolean canReadWebSocketFrames();

    /**
     * Test if Connection State allows for writing frames.
     *
     * @return true if able to write, false otherwise.
     */
    boolean canWriteWebSocketFrames();

    /**
     * Close the connection based on the cause.
     *
     * @param cause the cause
     */
    void close(Throwable cause);

    /**
     * Request a local close.
     */
    void close(CloseInfo closeInfo, Callback callback);

    /**
     * Terminate the connection (no close frame sent)
     */
    void disconnect();

    /**
     * Get the ByteBufferPool in use by the connection
     *
     * @return the buffer pool
     */
    ByteBufferPool getBufferPool();

    /**
     * Get the Executor used by this connection.
     *
     * @return the executor
     */
    Executor getExecutor();

    /**
     * Get Unique ID for the Connection
     *
     * @return the unique ID for the connection
     */
    String getId();

    /**
     * Get the read/write idle timeout.
     *
     * @return the idle timeout in milliseconds
     */
    long getIdleTimeout();

    /**
     * Get the local {@link InetSocketAddress} in use for this connection.
     * <p>
     * Note: Non-physical connections, like during the Mux extensions, or during unit testing can result in a InetSocketAddress on port 0 and/or on localhost.
     *
     * @return the local address.
     */
    InetSocketAddress getLocalAddress();

    /**
     * Set the maximum number of milliseconds of idleness before the connection is closed/disconnected, (ie no frames are either sent or received)
     *
     * @return the idle timeout in milliseconds
     */
    long getMaxIdleTimeout();

    /**
     * Set the maximum number of milliseconds of idleness before the connection is closed/disconnected, (ie no frames are either sent or received)
     * <p>
     * This idle timeout cannot be garunteed to take immediate effect for any active read/write actions.
     * New read/write actions will have this new idle timeout.
     *
     * @param ms the number of milliseconds of idle timeout
     */
    void setMaxIdleTimeout(long ms);

    /**
     * The policy that the connection is running under.
     *
     * @return the policy for the connection
     */
    WebSocketPolicy getPolicy();

    /**
     * Get the remote Address in use for this connection.
     * <p>
     * Note: Non-physical connections, like during the Mux extensions, or during unit testing can result in a InetSocketAddress on port 0 and/or on localhost.
     *
     * @return the remote address.
     */
    InetSocketAddress getRemoteAddress();

    /**
     * Test if logical connection is still open
     *
     * @return true if connection is open
     */
    boolean isOpen();

    /**
     * Tests if the connection is actively reading.
     *
     * @return true if connection is actively attempting to read.
     */
    boolean isReading();

    /**
     * Set the state to opened (the application onOpen() method has been called successfully).
     * <p>
     * Reads from network begin here.
     * </p>
     *
     * @return true if state is OPENED, false otherwise
     */
    boolean opened();

    /**
     * Set the state to upgrade/opening handshake has completed.
     *
     * @return true if state is OPENING, false otherwise
     */
    boolean opening();

    /**
     * Report that the Remote Endpoint CLOSE Frame has been received
     *
     * @param close the close frame details
     */
    void remoteClose(CloseInfo close);

    /**
     * Set where the connection should send the incoming frames to.
     * <p>
     * Often this is from the Parser to the start of the extension stack, and eventually on to the session.
     *
     * @param incoming the incoming frames handler
     */
    void setNextIncomingFrames(IncomingFrames incoming);

    /**
     * Associate the Active Session with the connection.
     *
     * @param session the session for this connection
     */
    void setSession(WebSocketSession session);

    /**
     * Suspend a the incoming read events on the connection.
     *
     * @return the suspend token
     */
    SuspendToken suspend();

    /**
     * Get the Connection State as a String
     *
     * @return the Connection State string
     */
    String toStateString();
}
