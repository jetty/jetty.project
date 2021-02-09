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

package org.eclipse.jetty.websocket.tests.client;

import java.util.concurrent.CountDownLatch;

import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.common.WebSocketSession;
import org.eclipse.jetty.websocket.common.WebSocketSessionListener;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ClientOpenSessionTracker implements Connection.Listener, WebSocketSessionListener
{
    private final CountDownLatch closeSessionLatch;
    private final CountDownLatch closeConnectionLatch;

    public ClientOpenSessionTracker(int expectedSessions)
    {
        this.closeSessionLatch = new CountDownLatch(expectedSessions);
        this.closeConnectionLatch = new CountDownLatch(expectedSessions);
    }

    public void addTo(WebSocketClient client)
    {
        client.addSessionListener(this);
        client.addBean(this);
    }

    public void assertClosedProperly(WebSocketClient client) throws InterruptedException
    {
        assertTrue(closeConnectionLatch.await(5, SECONDS), "All Jetty Connections should have been closed");
        assertTrue(closeSessionLatch.await(5, SECONDS), "All WebSocket Sessions should have been closed");
        assertTrue(client.getOpenSessions().isEmpty(), "Client OpenSessions MUST be empty");
    }

    @Override
    public void onOpened(Connection connection)
    {
    }

    @Override
    public void onClosed(Connection connection)
    {
        this.closeConnectionLatch.countDown();
    }

    @Override
    public void onSessionClosed(WebSocketSession session)
    {
        this.closeSessionLatch.countDown();
    }
}
