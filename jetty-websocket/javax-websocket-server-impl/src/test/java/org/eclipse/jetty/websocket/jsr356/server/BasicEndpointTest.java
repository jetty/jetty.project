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

package org.eclipse.jetty.websocket.jsr356.server;

import java.net.URI;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.common.test.Timeouts;
import org.eclipse.jetty.websocket.jsr356.server.samples.echo.BasicEchoEndpoint;
import org.eclipse.jetty.websocket.jsr356.server.samples.echo.BasicEchoEndpointConfigContextListener;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Example of an {@link javax.websocket.Endpoint} extended echo server added programmatically via the
 * {@link ServerContainer#addEndpoint(javax.websocket.server.ServerEndpointConfig)}
 */
@ExtendWith(WorkDirExtension.class)
public class BasicEndpointTest
{
    public WorkDir testdir;

    public ByteBufferPool bufferPool = new MappedByteBufferPool();

    @Test
    public void testEcho() throws Exception
    {
        WSServer wsb = new WSServer(testdir, "app");
        wsb.copyWebInf("basic-echo-endpoint-config-web.xml");
        // the endpoint (extends javax.websocket.Endpoint)
        wsb.copyClass(BasicEchoEndpoint.class);
        // the configuration (adds the endpoint)
        wsb.copyClass(BasicEchoEndpointConfigContextListener.class);

        try
        {
            wsb.start();
            URI uri = wsb.getServerBaseURI();

            WebAppContext webapp = wsb.createWebAppContext();
            wsb.deployWebapp(webapp);

            WebSocketClient client = new WebSocketClient(bufferPool);
            try
            {
                client.start();
                JettyEchoSocket clientEcho = new JettyEchoSocket();
                Future<Session> future = client.connect(clientEcho, uri.resolve("echo"));
                // wait for connect
                future.get(1, TimeUnit.SECONDS);
                clientEcho.sendMessage("Hello World");
                LinkedBlockingQueue<String> msgs = clientEcho.incomingMessages;
                assertEquals("Hello World", msgs.poll(Timeouts.POLL_EVENT, Timeouts.POLL_EVENT_UNIT), "Expected message");
            }
            finally
            {
                client.stop();
            }
        }
        finally
        {
            wsb.stop();
        }
    }
}
