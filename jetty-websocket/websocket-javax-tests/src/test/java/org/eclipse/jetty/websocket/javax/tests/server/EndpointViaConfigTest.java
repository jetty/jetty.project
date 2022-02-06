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

package org.eclipse.jetty.websocket.javax.tests.server;

import java.net.URI;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.acme.websocket.BasicEchoEndpoint;
import com.acme.websocket.BasicEchoEndpointConfigContextListener;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.core.CoreSession;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.core.client.WebSocketCoreClient;
import org.eclipse.jetty.websocket.javax.tests.WSServer;
import org.eclipse.jetty.websocket.javax.tests.framehandlers.FrameHandlerTracker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Example of an {@link javax.websocket.Endpoint} extended echo server added programmatically via the
 * {@link javax.websocket.server.ServerContainer#addEndpoint(javax.websocket.server.ServerEndpointConfig)}
 */
@ExtendWith(WorkDirExtension.class)
public class EndpointViaConfigTest
{
    private static final Logger LOG = LoggerFactory.getLogger(EndpointViaConfigTest.class);

    public WorkDir testdir;

    @Test
    public void testEcho() throws Exception
    {
        WSServer wsb = new WSServer(testdir.getPath());
        WSServer.WebApp app = wsb.createWebApp("app");
        app.copyWebInf("basic-echo-endpoint-config-web.xml");
        // the endpoint (extends javax.websocket.Endpoint)
        app.copyClass(BasicEchoEndpoint.class);
        // the configuration (adds the endpoint)
        app.copyClass(BasicEchoEndpointConfigContextListener.class);
        app.deploy();

        try
        {
            wsb.start();
            URI uri = wsb.getWsUri();

            WebSocketCoreClient client = new WebSocketCoreClient();
            try
            {
                client.start();
                FrameHandlerTracker clientSocket = new FrameHandlerTracker();
                Future<CoreSession> clientConnectFuture = client.connect(clientSocket, uri.resolve("/app/echo"));
                // wait for connect
                CoreSession coreSession = clientConnectFuture.get(5, TimeUnit.SECONDS);
                try
                {
                    coreSession.sendFrame(new Frame(OpCode.TEXT).setPayload("Hello World"), Callback.NOOP, false);

                    String incomingMessage = clientSocket.messageQueue.poll(1, TimeUnit.SECONDS);
                    assertThat("Expected message", incomingMessage, is("Hello World"));
                }
                finally
                {
                    coreSession.close(Callback.NOOP);
                }
            }
            finally
            {
                client.stop();
                LOG.debug("Stopped - " + client);
            }
        }
        finally
        {
            wsb.stop();
            LOG.debug("Stopped - " + wsb);
        }
    }
}
