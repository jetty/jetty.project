// ========================================================================
// Copyright 2011-2012 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
//
//     The Eclipse Public License is available at
//     http://www.eclipse.org/legal/epl-v10.html
//
//     The Apache License v2.0 is available at
//     http://www.opensource.org/licenses/apache2.0.php
//
// You may elect to redistribute this code under either of these licenses.
//========================================================================
package org.eclipse.jetty.websocket.io;

import java.io.IOException;
import java.util.concurrent.Executor;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.api.WebSocketConnection;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.protocol.Generator;
import org.eclipse.jetty.websocket.protocol.Parser;
import org.eclipse.jetty.websocket.protocol.WebSocketFrame;

/**
 * Interface for working with connections in a raw way.
 * <p>
 * This is abstracted out to allow for common access to connection internals regardless of physical vs virtual connections.
 */
public interface RawConnection extends WebSocketConnection
{
    void close() throws IOException;

    <C> void complete(FrameBytes<C> frameBytes);

    void disconnect(boolean onlyOutput);

    void flush();

    ByteBufferPool getBufferPool();

    Executor getExecutor();

    Generator getGenerator();

    Parser getParser();

    WebSocketPolicy getPolicy();

    FrameQueue getQueue();

    <C> void write(C context, Callback<C> callback, WebSocketFrame frame);
}
