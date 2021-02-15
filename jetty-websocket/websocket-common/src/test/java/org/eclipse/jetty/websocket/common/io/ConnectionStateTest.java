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

package org.eclipse.jetty.websocket.common.io;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ConnectionStateTest
{
    @Test
    public void testHandshakeToOpened()
    {
        ConnectionState state = new ConnectionState();

        assertFalse(state.canWriteWebSocketFrames(), "Handshaking canWriteWebSocketFrames");
        assertFalse(state.canReadWebSocketFrames(), "Handshaking canReadWebSocketFrames");

        assertTrue(state.opening(), "Opening");

        assertTrue(state.canWriteWebSocketFrames(), "Opening canWriteWebSocketFrames");
        assertFalse(state.canReadWebSocketFrames(), "Opening canReadWebSocketFrames");

        assertTrue(state.opened(), "Opened");

        assertTrue(state.canWriteWebSocketFrames(), "Opened canWriteWebSocketFrames");
        assertTrue(state.canReadWebSocketFrames(), "Opened canReadWebSocketFrames");
    }

    @Test
    public void testOpenedClosing()
    {
        ConnectionState state = new ConnectionState();
        assertTrue(state.opening(), "Opening");
        assertTrue(state.opened(), "Opened");

        assertTrue(state.closing(), "Closing (initial)");

        // A closing state allows for read, but not write
        assertFalse(state.canWriteWebSocketFrames(), "Closing canWriteWebSocketFrames");
        assertTrue(state.canReadWebSocketFrames(), "Closing canReadWebSocketFrames");

        // Closing again shouldn't allow for another close frame to be sent
        assertFalse(state.closing(), "Closing (extra)");
    }

    @Test
    public void testOpenedClosingDisconnected()
    {
        ConnectionState state = new ConnectionState();
        assertTrue(state.opening(), "Opening");
        assertTrue(state.opened(), "Opened");
        assertTrue(state.closing(), "Closing");

        assertTrue(state.disconnected(), "Disconnected");
        assertFalse(state.canWriteWebSocketFrames(), "Disconnected canWriteWebSocketFrames");
        assertFalse(state.canReadWebSocketFrames(), "Disconnected canReadWebSocketFrames");
    }

    @Test
    public void testOpenedHarshDisconnected()
    {
        ConnectionState state = new ConnectionState();
        assertTrue(state.opening(), "Opening");
        assertTrue(state.opened(), "Opened");
        // INTENTIONALLY HAD NO CLOSING - assertTrue(state.closing(), "Closing");

        assertTrue(state.disconnected(), "Disconnected");
        assertFalse(state.canWriteWebSocketFrames(), "Disconnected canWriteWebSocketFrames");
        assertFalse(state.canReadWebSocketFrames(), "Disconnected canReadWebSocketFrames");
    }
}
