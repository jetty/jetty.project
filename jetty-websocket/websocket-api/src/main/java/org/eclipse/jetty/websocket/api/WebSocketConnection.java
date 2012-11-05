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

package org.eclipse.jetty.websocket.api;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.concurrent.Future;

/**
 * Connection interface for WebSocket protocol <a href="https://tools.ietf.org/html/rfc6455">RFC-6455</a>.
 */
public interface WebSocketConnection
{
    /**
     * Send a websocket Close frame, without a status code or reason.
     * <p>
     * Basic usage: results in an non-blocking async write, then connection close.
     * 
     * @see StatusCode
     * @see #close(int, String)
     */
    public void close();

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
    public void close(int statusCode, String reason);
    
    /**
     * Is the connection open.
     * 
     * @return true if open
     */
    public boolean isOpen();
    
    /**
     * Suspend a the incoming read events on the connection.
     * 
     * @return the suspend token suitable for resuming the reading of data on the connection. 
     */
    SuspendToken suspend();
    
    /**
     * Get the address of the remote side.
     * 
     * @return the remote side address
     */
    public InetSocketAddress getRemoteAddress();
    
    /**
     * Get the address of the local side.
     * 
     * @return the local side address
     */
    public InetSocketAddress getLocalAddress();
    
    /**
     * Get the Request URI
     * 
     * @return the requested URI
     */
    public URI getRequestURI();
    
    /**
     * Access the (now read-only) {@link WebSocketPolicy} in use for this connection.
     * 
     * @return the policy in use
     */
    WebSocketPolicy getPolicy();

    /**
     * Get the SubProtocol in use for this connection.
     * 
     * @return the negotiated sub protocol name in use for this connection, can be null if there is no sub-protocol negotiated.
     */
    String getSubProtocol();

    /**
     * Send a single ping messages.
     * <p>
     * NIO style with callbacks, allows for knowledge of successful ping send.
     * <p>
     * Use @OnWebSocketFrame and monitor Pong frames
     */
    <C> Future<C> ping(C context, byte payload[]) throws IOException;

    /**
     * Send a a binary message.
     * <p>
     * NIO style with callbacks, allows for concurrent results of the write operation.
     */
    <C> Future<C> write(C context, byte buf[], int offset, int len) throws IOException;

    /**
     * Send a a binary message.
     * <p>
     * NIO style with callbacks, allows for concurrent results of the write operation.
     */
    <C> Future<C> write(C context, ByteBuffer buffer) throws IOException;

    /**
     * Send a series of text messages.
     * <p>
     * NIO style with callbacks, allows for concurrent results of the entire write operation. (Callback is only called once at the end of processing all of the
     * messages)
     */
    <C> Future<C> write(C context, String message) throws IOException;
}