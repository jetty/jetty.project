//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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
import org.eclipse.jetty.websocket.api.SuspendToken;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.api.extensions.IncomingFrames;
import org.eclipse.jetty.websocket.api.extensions.OutgoingFrames;
import org.eclipse.jetty.websocket.common.io.IOState;

public interface LogicalConnection extends OutgoingFrames, SuspendToken
{
    /**
     * Called to indicate a close frame was successfully sent to the remote.
     * @param close the close details
     */
    void onLocalClose(CloseInfo close);

    /**
     * Terminate the connection (no close frame sent)
     */
    void disconnect();

    /**
     * Get the ByteBufferPool in use by the connection
     * @return the buffer pool
     */
    ByteBufferPool getBufferPool();
    
    /**
     * Get the Executor used by this connection.
     * @return the executor
     */
    Executor getExecutor();

    /**
     * Get the read/write idle timeout.
     * 
     * @return the idle timeout in milliseconds
     */
    long getIdleTimeout();

    /**
     * Get the IOState of the connection.
     * 
     * @return the IOState of the connection.
     */
    IOState getIOState();

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
     * @return the idle timeout in milliseconds
     */
    long getMaxIdleTimeout();

    /**
     * The policy that the connection is running under.
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
     *  @return true if connection is open
     */
    boolean isOpen();

    /**
     * Tests if the connection is actively reading.
     * 
     * @return true if connection is actively attempting to read.
     */
    boolean isReading();

    /**
     * Set the maximum number of milliseconds of idleness before the connection is closed/disconnected, (ie no frames are either sent or received)
     * <p>
     * This idle timeout cannot be garunteed to take immediate effect for any active read/write actions.
     * New read/write actions will have this new idle timeout.
     * 
     * @param ms
     *            the number of milliseconds of idle timeout
     */
    void setMaxIdleTimeout(long ms);

    /**
     * Set where the connection should send the incoming frames to.
     * <p>
     * Often this is from the Parser to the start of the extension stack, and eventually on to the session.
     * 
     * @param incoming
     *            the incoming frames handler
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
     * @return the suspend token
     */
    SuspendToken suspend();

    /**
     * Get Unique ID for the Connection
     * @return the unique ID for the connection
     */
    String getId();
}
