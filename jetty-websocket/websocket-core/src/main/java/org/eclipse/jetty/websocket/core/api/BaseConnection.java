//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.core.api;

import java.net.InetSocketAddress;

import org.eclipse.jetty.websocket.core.protocol.CloseInfo;
import org.eclipse.jetty.websocket.core.protocol.ConnectionState;

/**
 * Base Connection concepts
 */
public interface BaseConnection
{
    /**
     * Connection suspend token
     */
    public static interface SuspendToken
    {
        /**
         * Resume a previously suspended connection.
         */
        void resume();
    }

    /**
     * Send a websocket Close frame, without a status code or reason.
     * <p>
     * Basic usage: results in an non-blocking async write, then connection close.
     * 
     * @see StatusCode
     * @see #close(int, String)
     */
    void close();

    /**
     * Send a websocket Close frame, with status code.
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
     * Terminate the connection (no close frame sent)
     */
    void disconnect();

    /**
     * Get the remote Address in use for this connection.
     * 
     * @return the remote address if available. (situations like mux extension and proxying makes this information unreliable)
     */
    InetSocketAddress getRemoteAddress();

    /**
     * Get the WebSocket connection State.
     * 
     * @return the connection state.
     */
    ConnectionState getState();

    /**
     * Test if input is closed (as a result of receiving a close frame)
     * 
     * @return true if input is closed.
     */
    boolean isInputClosed();

    /**
     * Simple test to see if connection is open (and not closed)
     * 
     * @return true if connection still open
     */
    boolean isOpen();

    /**
     * Test if output is closed (as a result of sending a close frame)
     * 
     * @return true if output is closed.
     */
    boolean isOutputClosed();

    /**
     * Tests if the connection is actively reading.
     * 
     * @return true if connection is actively attempting to read.
     */
    boolean isReading();

    /**
     * A close handshake frame has been detected
     * 
     * @param incoming
     *            true if part of an incoming frame, false if part of an outgoing frame.
     * @param close
     *            the close details
     */
    void onCloseHandshake(boolean incoming, CloseInfo close);

    /**
     * Suspend a the incoming read events on the connection.
     * 
     * @return
     */
    SuspendToken suspend();
}
