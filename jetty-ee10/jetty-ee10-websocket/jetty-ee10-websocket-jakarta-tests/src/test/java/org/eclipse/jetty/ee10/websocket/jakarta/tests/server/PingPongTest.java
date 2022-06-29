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

package org.eclipse.jetty.ee10.websocket.jakarta.tests.server;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import com.acme.websocket.PongContextListener;
import com.acme.websocket.PongMessageEndpoint;
import com.acme.websocket.PongSocket;
import org.eclipse.jetty.ee10.websocket.jakarta.tests.Timeouts;
import org.eclipse.jetty.ee10.websocket.jakarta.tests.WSServer;
import org.eclipse.jetty.ee10.websocket.jakarta.tests.framehandlers.FrameHandlerTracker;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.core.CoreSession;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.core.client.WebSocketCoreClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertTimeout;

public class PingPongTest
{
    private static WSServer server;
    private static WebSocketCoreClient client;

    @BeforeAll
    public static void startServer() throws Exception
    {
        Path testdir = MavenTestingUtils.getTargetTestingPath(PingPongTest.class.getName());
        server = new WSServer(testdir);

        WSServer.WebApp app = server.createWebApp("app");
        app.copyWebInf("pong-config-web.xml");
        app.copyClass(PongContextListener.class);
        app.copyClass(PongMessageEndpoint.class);
        app.copyClass(PongSocket.class);
        app.deploy();

        server.start();
    }

    @BeforeAll
    public static void startClient() throws Exception
    {
        client = new WebSocketCoreClient();
        client.start();
    }

    @AfterAll
    public static void stopServer() throws Exception
    {
        server.stop();
    }

    private void assertEcho(String endpointPath, Consumer<CoreSession> sendAction, String... expectedMsgs) throws Exception
    {
        FrameHandlerTracker clientSocket = new FrameHandlerTracker();
        URI toUri = server.getWsUri().resolve(endpointPath);

        // Connect
        Future<CoreSession> futureSession = client.connect(clientSocket, toUri);
        CoreSession coreSession = futureSession.get(Timeouts.CONNECT_MS, TimeUnit.MILLISECONDS);
        try
        {
            // Apply send action
            sendAction.accept(coreSession);

            // Validate Responses
            for (int i = 0; i < expectedMsgs.length; i++)
            {
                String pingMsg = clientSocket.messageQueue.poll(1, TimeUnit.SECONDS);
                assertThat("Expected message[" + i + "]", pingMsg, containsString(expectedMsgs[i]));
            }
        }
        finally
        {
            coreSession.close(Callback.NOOP);
        }
    }

    @Test
    public void testPongEndpoint() throws Exception
    {
        assertTimeout(Duration.ofMillis(6000), () ->
        {
            assertEcho("/app/pong", (session) ->
            {
                session.sendFrame(new Frame(OpCode.PONG).setPayload("hello"), Callback.NOOP, false);
            }, "PongMessageEndpoint.onMessage(PongMessage):[/pong]:hello");
        });
    }

    @Test
    public void testPongSocket() throws Exception
    {
        assertTimeout(Duration.ofMillis(6000), () ->
        {
            assertEcho("/app/pong-socket", (session) ->
            {
                session.sendFrame(new Frame(OpCode.PONG).setPayload("hello"), Callback.NOOP, false);
            }, "PongSocket.onPong(PongMessage)[/pong-socket]:hello");
        });
    }
}
