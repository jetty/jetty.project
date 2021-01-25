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

import java.io.File;
import java.net.URI;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.common.test.Timeouts;
import org.eclipse.jetty.websocket.jsr356.server.samples.beans.DateDecoder;
import org.eclipse.jetty.websocket.jsr356.server.samples.beans.TimeEncoder;
import org.eclipse.jetty.websocket.jsr356.server.samples.echo.ConfiguredEchoSocket;
import org.eclipse.jetty.websocket.jsr356.server.samples.echo.EchoSocketConfigurator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;

/**
 * Example of an annotated echo server discovered via annotation scanning.
 */
public class AnnotatedServerEndpointTest
{
    public ByteBufferPool bufferPool = new MappedByteBufferPool();

    private static WSServer server;

    @BeforeAll
    public static void startServer() throws Exception
    {
        File testdir = MavenTestingUtils.getTargetTestingDir(AnnotatedServerEndpointTest.class.getName());
        server = new WSServer(testdir, "app");
        server.createWebInf();
        server.copyEndpoint(ConfiguredEchoSocket.class);
        server.copyClass(EchoSocketConfigurator.class);
        server.copyClass(DateDecoder.class);
        server.copyClass(TimeEncoder.class);

        server.start();

        WebAppContext webapp = server.createWebAppContext();
        server.deployWebapp(webapp);
    }

    @AfterAll
    public static void stopServer()
    {
        server.stop();
    }

    private void assertResponse(String message, String... expectedTexts) throws Exception
    {
        WebSocketClient client = new WebSocketClient(bufferPool);
        try
        {
            client.start();
            JettyEchoSocket clientEcho = new JettyEchoSocket();
            URI uri = server.getServerBaseURI().resolve("echo");
            ClientUpgradeRequest req = new ClientUpgradeRequest();
            req.setSubProtocols("echo");
            Future<Session> foo = client.connect(clientEcho, uri, req);
            // wait for connect
            foo.get(1, TimeUnit.SECONDS);

            clientEcho.sendMessage(message);
            LinkedBlockingQueue<String> msgs = clientEcho.incomingMessages;

            String response = msgs.poll(Timeouts.POLL_EVENT, Timeouts.POLL_EVENT_UNIT);
            for (String expected : expectedTexts)
            {
                assertThat("Expected message", response, containsString(expected));
            }
        }
        finally
        {
            client.stop();
        }
    }

    @Test
    public void testConfigurator() throws Exception
    {
        assertResponse("configurator", EchoSocketConfigurator.class.getName());
    }

    @Test
    public void testTextMax() throws Exception
    {
        assertResponse("text-max", "111,222");
    }

    @Test
    public void testBinaryMax() throws Exception
    {
        assertResponse("binary-max", "333,444");
    }

    @Test
    public void testDecoders() throws Exception
    {
        assertResponse("decoders", DateDecoder.class.getName());
    }

    @Test
    public void testEncoders() throws Exception
    {
        assertResponse("encoders", TimeEncoder.class.getName());
    }

    @Test
    public void testSubProtocols() throws Exception
    {
        assertResponse("subprotocols", "chat, echo, test");
    }
}
