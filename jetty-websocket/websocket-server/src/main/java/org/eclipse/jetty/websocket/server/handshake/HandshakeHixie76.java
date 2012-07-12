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
package org.eclipse.jetty.websocket.server.handshake;

import java.io.IOException;

import org.eclipse.jetty.websocket.server.ServletWebSocketRequest;
import org.eclipse.jetty.websocket.server.ServletWebSocketResponse;
import org.eclipse.jetty.websocket.server.WebSocketHandshake;

/**
 * WebSocket Handshake for spec <a href="https://tools.ietf.org/html/draft-hixie-thewebsocketprotocol-76">Hixie-76 Draft</a>.
 * <p>
 * Most often seen in use by Safari/OSX
 */
public class HandshakeHixie76 implements WebSocketHandshake
{
    /** draft-hixie-thewebsocketprotocol-76 - Sec-WebSocket-Draft */
    public static final int VERSION = 0;

    @Override
    public void doHandshakeResponse(ServletWebSocketRequest request, ServletWebSocketResponse response) throws IOException
    {
        // TODO: implement the Hixie76 handshake?
        throw new IOException("Not implemented yet");
    }
}
