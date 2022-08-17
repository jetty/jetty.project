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

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import jakarta.websocket.CloseReason;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.DecodeException;
import jakarta.websocket.Decoder;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.WebSocketContainer;
import jakarta.websocket.server.PathParam;
import jakarta.websocket.server.ServerEndpoint;
import org.eclipse.jetty.ee10.webapp.WebAppContext;
import org.eclipse.jetty.ee10.websocket.jakarta.tests.EventSocket;
import org.eclipse.jetty.ee10.websocket.jakarta.tests.WSServer;
import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnJre;
import org.junit.jupiter.api.condition.JRE;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DeploymentTest
{
    private WSServer server;

    @BeforeEach
    public void startServer() throws Exception
    {
        Path testdir = MavenTestingUtils.getTargetTestingPath(DeploymentTest.class.getName());
        server = new WSServer(testdir);
    }

    @AfterEach
    public void stopServer() throws Exception
    {
        server.stop();
    }

    @Disabled
    @Test
    public void testBadPathParamSignature() throws Exception
    {
        WSServer.WebApp app1 = server.createWebApp("test1");
        app1.createWebInf();
        app1.copyClass(BadPathParamEndpoint.class);
        app1.copyClass(DecodedString.class);
        app1.copyClass(DeploymentTest.class);
        app1.deploy();
        app1.getWebAppContext().setThrowUnavailableOnStartupException(false);

        try (StacklessLogging ignore = new StacklessLogging(WebAppContext.class))
        {
            server.start();
        }

        WebSocketContainer client = ContainerProvider.getWebSocketContainer();
        EventSocket clientSocket = new EventSocket();

        Throwable error = assertThrows(Throwable.class, () ->
            client.connectToServer(clientSocket, server.getWsUri().resolve(app1.getContextPath() + "/badonclose/a")));
        assertThat(error, Matchers.instanceOf(IOException.class));
        assertThat(error.getMessage(), Matchers.containsString("503 Service Unavailable"));
    }

    /**
     * This reproduces some classloading issue with MethodHandles in JDK14-15, this has been fixed in JDK16.
     * @see <a href="https://bugs.openjdk.java.net/browse/JDK-8244090">JDK-8244090</a>
     * @throws Exception if there is an error during the test.
     */
    @Test
    @Disabled
    @DisabledOnJre({JRE.JAVA_14, JRE.JAVA_15})
    public void testDifferentWebAppsWithSameClassInSignature() throws Exception
    {
        WSServer.WebApp app1 = server.createWebApp("test1");
        app1.createWebInf();
        app1.copyClass(DecodedEndpoint.class);
        app1.copyClass(StringDecoder.class);
        app1.copyClass(DecodedString.class);
        app1.copyClass(DeploymentTest.class);
        app1.deploy();

        WSServer.WebApp app2 = server.createWebApp("test2");
        app2.createWebInf();
        app2.copyClass(DecodedEndpoint.class);
        app2.copyClass(StringDecoder.class);
        app2.copyClass(DecodedString.class);
        app2.copyClass(DeploymentTest.class);
        app2.deploy();

        server.start();
        WebSocketContainer client = ContainerProvider.getWebSocketContainer();
        EventSocket clientSocket = new EventSocket();

        // Test echo and close to endpoint at /test1.
        Session session = client.connectToServer(clientSocket, server.getWsUri().resolve("/test1"));
        session.getAsyncRemote().sendText("hello world");
        assertThat(clientSocket.textMessages.poll(5, TimeUnit.SECONDS), is("hello world"));
        session.close();
        assertTrue(clientSocket.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(clientSocket.closeReason.getCloseCode(), is(CloseReason.CloseCodes.NORMAL_CLOSURE));

        // Test echo and close to endpoint at /test2.
        session = client.connectToServer(clientSocket, server.getWsUri().resolve("/test2"));
        session.getAsyncRemote().sendText("hello world");
        assertThat(clientSocket.textMessages.poll(5, TimeUnit.SECONDS), is("hello world"));
        session.close();
        assertTrue(clientSocket.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(clientSocket.closeReason.getCloseCode(), is(CloseReason.CloseCodes.NORMAL_CLOSURE));
    }

    @ServerEndpoint("/badonopen/{arg}")
    public static class BadPathParamEndpoint
    {
        @OnOpen
        public void onOpen(Session session, @PathParam("arg")  DecodedString arg)
        {
        }
    }

    @ServerEndpoint(value = "/", decoders = {StringDecoder.class})
    public static class DecodedEndpoint
    {
        @OnMessage
        public void onMessage(Session session, DecodedString message)
        {
            session.getAsyncRemote().sendText(message.getString());
        }
    }

    public static class DecodedString
    {
        public String string = "";

        public DecodedString(String hold)
        {
            string = hold;
        }

        public String getString()
        {
            return string;
        }
    }

    public static class StringDecoder implements Decoder.Text<DecodedString>
    {
        @Override
        public DecodedString decode(String s) throws DecodeException
        {
            return new DecodedString(s);
        }

        @Override
        public void init(EndpointConfig config)
        {
        }

        @Override
        public void destroy()
        {
        }

        @Override
        public boolean willDecode(String s)
        {
            return true;
        }
    }
}
