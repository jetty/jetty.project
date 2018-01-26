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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThat;

import java.net.SocketAddress;
import java.net.URI;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.WSURI;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.common.HandshakeRequest;
import org.eclipse.jetty.websocket.tests.Defaults;
import org.eclipse.jetty.websocket.tests.LocalServer;
import org.eclipse.jetty.websocket.tests.TrackingEndpoint;
import org.eclipse.jetty.websocket.tests.server.servlets.EchoSocket;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.TestName;

public class WebSocketClientTest
{
    @Rule
    public TestName testname = new TestName();

    @Rule
    public ExpectedException expectedException = ExpectedException.none();

    private LocalServer server;
    private WebSocketClient client;

    @Before
    public void startClient() throws Exception
    {
        client = new WebSocketClient();
        client.start();
    }

    @Before
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

    @After
    public void stopClient() throws Exception
    {
        client.stop();
    }

    @After
    public void stopServer() throws Exception
    {
        server.stop();
    }

    @Test
    public void testAddExtension_NotInstalled() throws Exception
    {
        TrackingEndpoint clientEndpoint = new TrackingEndpoint(testname.getMethodName());

        client.getPolicy().setIdleTimeout(10000);

        ClientUpgradeRequest request = new ClientUpgradeRequest();
        request.setSubProtocols("echo");
        request.addExtensions("x-bad"); // extension that doesn't exist

        // Should trigger failure on bad extension
        expectedException.expect(IllegalArgumentException.class);
        expectedException.expectMessage(containsString("x-bad"));
        URI wsUri = WSURI.toWebsocket(server.getServerUri());
        client.connect(clientEndpoint, wsUri, request);
    }

    @Test
    public void testBasicEcho() throws Exception
    {
        // Set client timeout
        final int timeout = 1000;
        client.setMaxIdleTimeout(timeout);

        // Client connects
        TrackingEndpoint clientEndpoint = new TrackingEndpoint(testname.getMethodName());
        ClientUpgradeRequest clientUpgradeRequest = new ClientUpgradeRequest();
        clientUpgradeRequest.setSubProtocols("echo");
        URI wsUri = server.getWsUri();
        Future<Session> clientConnectFuture = client.connect(clientEndpoint, wsUri, clientUpgradeRequest);

        // Verify Client Session
        Session clientSession = clientConnectFuture.get(Defaults.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        assertThat("Client Session", clientSession, notNullValue());
        assertThat("Client Session.onOpen", clientSession.isOpen(), is(true));
        assertThat("Client Session.upgradeRequest", clientSession.getHandshakeRequest(), notNullValue());
        assertThat("Client Session.upgradeRequest", clientSession.getHandshakeResponse(), notNullValue());

        // Verify Client Session Tracking
        Collection<Session> sessions = client.getOpenSessions();
        Assert.assertThat("client.beans[session].size", sessions.size(), is(1));

        // client confirms connection via echo
        clientEndpoint.awaitOpenEvent("Client");

        // client sends message
        clientEndpoint.getRemote().sendText("Hello Echo");

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
    public void testBasicEcho_UsingCallback() throws Exception
    {
        client.setMaxIdleTimeout(160000);
        TrackingEndpoint clientEndpoint = new TrackingEndpoint(testname.getMethodName());
        ClientUpgradeRequest request = new ClientUpgradeRequest();
        request.setSubProtocols("echo");
        URI wsUri = WSURI.toWebsocket(server.getServerUri());
        Future<Session> clientConnectFuture = client.connect(clientEndpoint, wsUri, request);

        Session clientSession = clientConnectFuture.get(Defaults.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        assertThat("Client session", clientSession, notNullValue());

        FutureCallback callback = new FutureCallback();
        clientEndpoint.session.getRemote().sendText("Hello World!", callback);
        callback.get(5, TimeUnit.SECONDS);
    }

    @Test
    public void testLocalRemoteAddress() throws Exception
    {
        TrackingEndpoint clientEndpoint = new TrackingEndpoint(testname.getMethodName());
        URI wsUri = WSURI.toWebsocket(server.getServerUri());
        Future<Session> clientConnectFuture = client.connect(clientEndpoint, wsUri);

        Session clientSession = clientConnectFuture.get(Defaults.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);

        SocketAddress local = clientSession.getHandshakeRequest().getLocalSocketAddress();
        SocketAddress remote = clientSession.getHandshakeRequest().getRemoteSocketAddress();

        Assert.assertThat("Local Socket Address", local, notNullValue());
        Assert.assertThat("Remote Socket Address", remote, notNullValue());
    }

    /**
     * Ensure that <code>@WebSocket(maxTextMessageSize = 100*1024)</code> behaves as expected.
     *
     * @throws Exception on test failure
     */
    @Test
    public void testMaxMessageSize() throws Exception
    {
        TrackingEndpoint clientEndpoint = new TrackingEndpoint(testname.getMethodName());
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

        clientSession.getRemote().sendText(outgoingMessage);

        String incomingMessage = clientEndpoint.messageQueue.poll(5, TimeUnit.SECONDS);
        assertThat("Message received", incomingMessage, is(outgoingMessage));
        clientSession.close();
    }

    @Test
    public void testParameterMap() throws Exception
    {
        TrackingEndpoint clientEndpoint = new TrackingEndpoint(testname.getMethodName());
        URI wsUri = WSURI.toWebsocket(server.getServerUri()).resolve("?snack=cashews&amount=handful&brand=off");
        assertThat("wsUri has query", wsUri.getQuery(), notNullValue());
        Future<Session> clientConnectFuture = client.connect(clientEndpoint, wsUri);

        Session clientSession = clientConnectFuture.get(Defaults.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);

        HandshakeRequest req = clientSession.getHandshakeRequest();
        Assert.assertThat("Upgrade Request", req, notNullValue());

        Map<String, List<String>> parameterMap = req.getParameterMap();
        Assert.assertThat("Parameter Map", parameterMap, notNullValue());

        Assert.assertThat("Parameter[snack]", parameterMap.get("snack"), is(Arrays.asList(new String[]{"cashews"})));
        Assert.assertThat("Parameter[amount]", parameterMap.get("amount"), is(Arrays.asList(new String[]{"handful"})));
        Assert.assertThat("Parameter[brand]", parameterMap.get("brand"), is(Arrays.asList(new String[]{"off"})));
        Assert.assertThat("Parameter[cost]", parameterMap.get("cost"), nullValue());
    }
}
