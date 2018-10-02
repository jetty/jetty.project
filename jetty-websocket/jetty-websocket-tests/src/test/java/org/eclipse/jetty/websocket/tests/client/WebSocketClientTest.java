//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

import java.net.SocketAddress;
import java.net.URI;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.WriteCallback;
import org.eclipse.jetty.websocket.api.util.WSURI;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.common.HandshakeRequest;
import org.eclipse.jetty.websocket.tests.Defaults;
import org.eclipse.jetty.websocket.tests.LocalServer;
import org.eclipse.jetty.websocket.tests.TrackingEndpoint;
import org.eclipse.jetty.websocket.tests.server.servlets.EchoSocket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class WebSocketClientTest
{
    private LocalServer server;
    private WebSocketClient client;

    @BeforeEach
    public void startClient() throws Exception
    {
        client = new WebSocketClient();
        client.start();
    }

    @BeforeEach
    public void startServer() throws Exception
    {
        server = new LocalServer();
        server.start();
        server.registerWebSocket("/*", (factory) -> {
            factory.getPolicy().setMaxTextMessageSize(200 * 1024);
            factory.setCreator((req, resp) -> {
                if (req.getSubProtocols().isEmpty())
                {
                    // no subprotocol, just echo.
                    return new EchoSocket();
                }

                if (req.hasSubProtocol("echo"))
                {
                    resp.setAcceptedSubProtocol("echo");
                    return new EchoSocket();
                }

                // Got an unrecognized subprotocol, no pojo supplied
                return null;
            });
        });
    }

    @AfterEach
    public void stopClient() throws Exception
    {
        client.stop();
    }

    @AfterEach
    public void stopServer() throws Exception
    {
        server.stop();
    }

    @Test
    public void testAddExtension_NotInstalled(TestInfo testInfo) throws Exception
    {
        TrackingEndpoint clientEndpoint = new TrackingEndpoint(testInfo.getTestMethod().toString());

        client.getPolicy().setIdleTimeout(10000);

        ClientUpgradeRequest request = new ClientUpgradeRequest();
        request.setSubProtocols("echo");
        request.addExtensions("x-bad"); // extension that doesn't exist

        // Should trigger failure on bad extension
        URI wsUri = WSURI.toWebsocket(server.getServerUri());
        Exception e = assertThrows(IllegalArgumentException.class, ()->client.connect(clientEndpoint, wsUri, request));
        assertThat(e.getMessage(), containsString("x-bad"));
    }

    @Test
    public void testBasicEcho(TestInfo testInfo) throws Exception
    {
        // Set client timeout
        final int timeout = 1000;
        client.setMaxIdleTimeout(timeout);

        // Client connects
        TrackingEndpoint clientEndpoint = new TrackingEndpoint(testInfo.getTestMethod().toString());
        ClientUpgradeRequest clientUpgradeRequest = new ClientUpgradeRequest();
        clientUpgradeRequest.setSubProtocols("echo");
        URI wsUri = server.getWsUri();
        Future<Session> clientConnectFuture = client.connect(clientEndpoint, wsUri, clientUpgradeRequest);

        // Verify Client Session
        Session clientSession = clientConnectFuture.get(Defaults.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        assertThat("Client Session", clientSession, notNullValue());
        assertThat("Client Session.onOpen", clientSession.isOpen(), is(true));
        assertThat("Client Session.upgradeRequest", clientSession.getUpgradeRequest(), notNullValue());
        assertThat("Client Session.upgradeRequest", clientSession.getUpgradeResponse(), notNullValue());

        // Verify Client Session Tracking
        Collection<Session> sessions = client.getOpenSessions();
        assertThat("client.beans[session].size", sessions.size(), is(1));

        // client confirms connection via echo
        clientEndpoint.awaitOpenEvent("Client");

        // client sends message
        clientEndpoint.getRemote().sendString("Hello Echo");

        // Wait for response to echo
        String message = clientEndpoint.messageQueue.poll(5, TimeUnit.SECONDS);
        assertThat("message", message, is("Hello Echo"));

        // client closes
        clientEndpoint.close(StatusCode.NORMAL, "Normal Close");

        // client triggers close event on client ws-endpoint
        clientEndpoint.awaitCloseEvent("Client");
        clientEndpoint.assertCloseStatus("Client", StatusCode.NORMAL, containsString("Normal Close"));
    }

    @Test
    public void testBasicEcho_UsingCallback(TestInfo testInfo) throws Exception
    {
        client.setMaxIdleTimeout(160000);
        TrackingEndpoint clientEndpoint = new TrackingEndpoint(testInfo.getTestMethod().toString());
        ClientUpgradeRequest request = new ClientUpgradeRequest();
        request.setSubProtocols("echo");
        URI wsUri = WSURI.toWebsocket(server.getServerUri());
        Future<Session> clientConnectFuture = client.connect(clientEndpoint, wsUri, request);

        Session clientSession = clientConnectFuture.get(Defaults.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        assertThat("Client session", clientSession, notNullValue());

        CountDownLatch latch = new CountDownLatch(1);
        clientEndpoint.session.getRemote().sendString("Hello World!", new WriteCallback()
        {
            @Override
            public void writeFailed(Throwable x)
            {
            }

            @Override
            public void writeSuccess()
            {
                latch.countDown();
            }
        });
        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testLocalRemoteAddress(TestInfo testInfo) throws Exception
    {
        TrackingEndpoint clientEndpoint = new TrackingEndpoint(testInfo.getTestMethod().toString());
        URI wsUri = WSURI.toWebsocket(server.getServerUri());
        Future<Session> clientConnectFuture = client.connect(clientEndpoint, wsUri);

        Session clientSession = clientConnectFuture.get(Defaults.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);

        fail();
        /* TODO
        SocketAddress local = clientSession.getUpgradeRequest().getLocalSocketAddress();
        SocketAddress remote = clientSession.getUpgradeRequest().getRemoteSocketAddress();

        assertThat("Local Socket Address", local, notNullValue());
        assertThat("Remote Socket Address", remote, notNullValue());
        */
    }

    /**
     * Ensure that <code>@WebSocket(maxTextMessageSize = 100*1024)</code> behaves as expected.
     *
     * @throws Exception on test failure
     */
    @Test
    public void testMaxMessageSize(TestInfo testInfo) throws Exception
    {
        TrackingEndpoint clientEndpoint = new TrackingEndpoint(testInfo.getTestMethod().toString());
        ClientUpgradeRequest upgradeRequest = new ClientUpgradeRequest();
        upgradeRequest.setSubProtocols("echo");
        client.getPolicy().setMaxTextMessageSize(100 * 1024);
        URI wsUri = WSURI.toWebsocket(server.getServerUri());
        Future<Session> clientConnectFuture = client.connect(clientEndpoint, wsUri, upgradeRequest);

        Session clientSession = clientConnectFuture.get(Defaults.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);

        // Create string that is larger than default size of 64k
        // but smaller than maxMessageSize of 100k
        byte buf[] = new byte[80 * 1024];
        Arrays.fill(buf, (byte) 'x');
        String outgoingMessage = StringUtil.toUTF8String(buf, 0, buf.length);

        clientSession.getRemote().sendString(outgoingMessage);

        String incomingMessage = clientEndpoint.messageQueue.poll(5, TimeUnit.SECONDS);
        assertThat("Message received", incomingMessage, is(outgoingMessage));
        clientSession.close();
    }

    @Test
    public void testParameterMap(TestInfo testInfo) throws Exception
    {
        TrackingEndpoint clientEndpoint = new TrackingEndpoint(testInfo.getTestMethod().toString());
        URI wsUri = WSURI.toWebsocket(server.getServerUri()).resolve("?snack=cashews&amount=handful&brand=off");
        assertThat("wsUri has query", wsUri.getQuery(), notNullValue());
        Future<Session> clientConnectFuture = client.connect(clientEndpoint, wsUri);

        Session clientSession = clientConnectFuture.get(Defaults.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);

        fail();
        /* TODO
        HandshakeRequest req = clientSession.getUpgradeRequest();
        assertThat("Upgrade Request", req, notNullValue());

        Map<String, List<String>> parameterMap = req.getParameterMap();
        assertThat("Parameter Map", parameterMap, notNullValue());

        assertThat("Parameter[snack]", parameterMap.get("snack"), is(Arrays.asList(new String[]{"cashews"})));
        assertThat("Parameter[amount]", parameterMap.get("amount"), is(Arrays.asList(new String[]{"handful"})));
        assertThat("Parameter[brand]", parameterMap.get("brand"), is(Arrays.asList(new String[]{"off"})));
        assertThat("Parameter[cost]", parameterMap.get("cost"), nullValue());
        */
    }
}
