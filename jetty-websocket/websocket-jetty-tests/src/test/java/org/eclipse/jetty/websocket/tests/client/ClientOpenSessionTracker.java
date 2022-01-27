//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.tests.client;

import java.util.concurrent.CountDownLatch;

import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketSessionListener;
import org.eclipse.jetty.websocket.client.WebSocketClient;

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
    public void onWebSocketSessionClosed(Session session)
    {
        this.closeSessionLatch.countDown();
    }
}
