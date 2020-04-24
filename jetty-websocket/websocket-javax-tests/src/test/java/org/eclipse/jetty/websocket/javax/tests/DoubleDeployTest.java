//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.javax.tests;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import javax.websocket.ContainerProvider;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;

import org.eclipse.jetty.tests.webapp.websocket.bad.BadOnCloseServerEndpoint;
import org.eclipse.jetty.tests.webapp.websocket.bad.BadOnOpenServerEndpoint;
import org.eclipse.jetty.tests.webapp.websocket.bad.StringSequence;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class DoubleDeployTest
{
    private WSServer server;
    private WSServer.WebApp app1;
    private WSServer.WebApp app2;

    @BeforeEach
    public void startServer() throws Exception
    {
        Path testdir = MavenTestingUtils.getTargetTestingPath(DoubleDeployTest.class.getName());
        server = new WSServer(testdir);

        app1 = server.createWebApp("test1");
        app1.createWebInf();
        app1.copyClass(BadOnOpenServerEndpoint.class);
        app1.copyClass(BadOnCloseServerEndpoint.class);
        app1.copyClass(StringSequence.class);
        app1.deploy();

        app2 = server.createWebApp("test2");
        app2.createWebInf();
        app2.copyClass(BadOnOpenServerEndpoint.class);
        app2.copyClass(BadOnCloseServerEndpoint.class);
        app2.copyClass(StringSequence.class);
        app2.deploy();

        server.start();
    }

    @AfterEach
    public void stopServer() throws Exception
    {
        server.stop();
    }

    @Test
    public void test() throws Exception
    {
        // Initially just test deployment. (We should fail at deployment anyway).
        if (true) return;

        WebSocketContainer client = ContainerProvider.getWebSocketContainer();
        EventSocket clientSocket = new EventSocket();
        Session session = client.connectToServer(clientSocket, server.getWsUri().resolve(app1.getContextPath() + "/badonclose/a"));
        session.getBasicRemote().sendText("test");
        session.close();
        assertTrue(clientSocket.closeLatch.await(5, TimeUnit.SECONDS));

        clientSocket = new EventSocket();
        session = client.connectToServer(clientSocket, server.getWsUri().resolve(app2.getContextPath() + "/badonopen/b"));
        session.getBasicRemote().sendText("test");
        session.close();
        assertTrue(clientSocket.closeLatch.await(5, TimeUnit.SECONDS));
    }
}
