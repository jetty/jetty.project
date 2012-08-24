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
import java.nio.ByteBuffer;

import org.eclipse.jetty.util.Callback;

/**
 * Connection interface for WebSocket protocol <a href="https://tools.ietf.org/html/rfc6455">RFC-6455</a>.
 */
public interface WebSocketConnection extends BaseConnection
{
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
    <C> void ping(C context, Callback<C> callback, byte payload[]) throws IOException;

    /**
     * Send a a binary message.
     * <p>
     * NIO style with callbacks, allows for concurrent results of the write operation.
     */
    <C> void write(C context, Callback<C> callback, byte buf[], int offset, int len) throws IOException;

    /**
     * Send a a binary message.
     * <p>
     * NIO style with callbacks, allows for concurrent results of the write operation.
     */
    <C> void write(C context, Callback<C> callback, ByteBuffer buffer) throws IOException;

    /**
     * Send a series of text messages.
     * <p>
     * NIO style with callbacks, allows for concurrent results of the entire write operation. (Callback is only called once at the end of processing all of the
     * messages)
     */
    <C> void write(C context, Callback<C> callback, String message) throws IOException;
}