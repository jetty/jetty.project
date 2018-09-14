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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.UpgradeRequest;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.common.WebSocketSession;
import org.eclipse.jetty.websocket.common.io.FutureWriteCallback;
import org.eclipse.jetty.websocket.tests.Defaults;
import org.eclipse.jetty.websocket.tests.TrackingEndpoint;
import org.eclipse.jetty.websocket.tests.UntrustedWSEndpoint;
import org.eclipse.jetty.websocket.tests.UntrustedWSServer;
import org.eclipse.jetty.websocket.tests.UntrustedWSSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

public class WebSocketClientTest
{
    private UntrustedWSServer server;
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
        server = new UntrustedWSServer();
        server.start();
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
        TrackingEndpoint clientEndpoint = new TrackingEndpoint(testInfo.getDisplayName());

        client.getPolicy().setIdleTimeout(10000);

        URI wsUri = server.getWsUri();
        ClientUpgradeRequest request = new ClientUpgradeRequest();
        request.setSubProtocols("echo");
        request.addExtensions("x-bad"); // extension that doesn't exist

        // Should trigger failure on bad extension
        IllegalArgumentException x = assertThrows(IllegalArgumentException.class, () ->
                client.connect(clientEndpoint, wsUri, request));
        assertThat(x.getMessage(), containsString("x-bad"));
    }

    @Test
    public void testBasicEcho(TestInfo testInfo) throws IOException, InterruptedException, ExecutionException, TimeoutException
    {
        // Set client timeout
        final int timeout = 1000;
        client.setMaxIdleTimeout(timeout);

        URI wsUri = server.getUntrustedWsUri(this.getClass(), testInfo);
        CompletableFuture<UntrustedWSSession> serverSessionFut = new CompletableFuture<>();
        server.registerOnOpenFuture(wsUri, serverSessionFut);

        // Client connects
        TrackingEndpoint clientEndpoint = new TrackingEndpoint(testInfo.getDisplayName());
        ClientUpgradeRequest clientUpgradeRequest = new ClientUpgradeRequest();
        clientUpgradeRequest.setSubProtocols("echo");
        Future<Session> clientConnectFuture = client.connect(clientEndpoint, wsUri, clientUpgradeRequest);

        // Verify Client Session
        Session clientSession = clientConnectFuture.get(Defaults.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        assertThat("Client Session", clientSession, notNullValue());
        assertThat("Client Session.open", clientSession.isOpen(), is(true));
        assertThat("Client Session.upgradeRequest", clientSession.getUpgradeRequest(), notNullValue());
        assertThat("Client Session.upgradeRequest", clientSession.getUpgradeResponse(), notNullValue());

        // Verify Client Session Tracking
        Collection<WebSocketSession> sessions = client.getBeans(WebSocketSession.class);
        assertThat("client.beans[session].size", sessions.size(), is(1));

        // Server accepts connect
        UntrustedWSSession serverSession = serverSessionFut.get(Defaults.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        UntrustedWSEndpoint serverEndpoint = serverSession.getUntrustedEndpoint();

        // client confirms connection via echo
        clientEndpoint.awaitOpenEvent("Client");

        // client sends message
        clientEndpoint.getRemote().sendString("Hello Echo");

        // Wait for response to echo
        String message = clientEndpoint.messageQueue.poll(5, TimeUnit.SECONDS);
        assertThat("message", message, is("Hello Echo"));

        // client closes
        clientEndpoint.close(StatusCode.NORMAL, "Normal Close");

        // Server close event
        serverEndpoint.awaitCloseEvent("Server");
        serverEndpoint.assertCloseInfo("Server", StatusCode.NORMAL, containsString("Normal Close"));

        // client triggers close event on client ws-endpoint
        clientEndpoint.awaitCloseEvent("Client");
        clientEndpoint.assertCloseInfo("Client", StatusCode.NORMAL, containsString("Normal Close"));
    }

    @Test
    public void testBasicEcho_UsingCallback(TestInfo testInfo) throws Exception
    {
        client.setMaxIdleTimeout(160000);
        TrackingEndpoint clientEndpoint = new TrackingEndpoint(testInfo.getDisplayName());
        URI wsUri = server.getUntrustedWsUri(this.getClass(), testInfo);
        ClientUpgradeRequest request = new ClientUpgradeRequest();
        request.setSubProtocols("echo");
        Future<Session> clientConnectFuture = client.connect(clientEndpoint, wsUri, request);

        Session clientSession = clientConnectFuture.get(Defaults.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        assertThat("Client session", clientSession, notNullValue());

        FutureWriteCallback callback = new FutureWriteCallback();
        clientEndpoint.session.getRemote().sendString("Hello World!", callback);
        callback.get(5, TimeUnit.SECONDS);
    }

    @Test
    public void testLocalRemoteAddress(TestInfo testInfo) throws Exception
    {
        TrackingEndpoint clientEndpoint = new TrackingEndpoint(testInfo);
        URI wsUri = server.getUntrustedWsUri(this.getClass(), testInfo);
        Future<Session> clientConnectFuture = client.connect(clientEndpoint, wsUri);

        Session clientSession = clientConnectFuture.get(Defaults.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);

        InetSocketAddress local = clientSession.getLocalAddress();
        InetSocketAddress remote = clientSession.getRemoteAddress();

        assertThat("Local Socket Address", local, notNullValue());
        assertThat("Remote Socket Address", remote, notNullValue());

        // Hard to validate (in a portable unit test) the local address that was used/bound in the low level Jetty Endpoint
        assertThat("Local Socket Address / Host", local.getAddress().getHostAddress(), notNullValue());
        assertThat("Local Socket Address / Port", local.getPort(), greaterThan(0));

        assertThat("Remote Socket Address / Host", remote.getAddress().getHostAddress(), is(wsUri.getHost()));
        assertThat("Remote Socket Address / Port", remote.getPort(), greaterThan(0));
    }

    /**
     * Ensure that <code>@WebSocket(maxTextMessageSize = 100*1024)</code> behaves as expected.
     *
     * @throws Exception on test failure
     */
    @Test
    public void testMaxMessageSize(TestInfo testInfo) throws Exception
    {
        TrackingEndpoint clientEndpoint = new TrackingEndpoint(testInfo);
        URI wsUri = server.getUntrustedWsUri(this.getClass(), testInfo);
        ClientUpgradeRequest upgradeRequest = new ClientUpgradeRequest();
        upgradeRequest.setSubProtocols("echo");
        client.getPolicy().setMaxTextMessageSize(100 * 1024);
        Future<Session> clientConnectFuture = client.connect(clientEndpoint, wsUri, upgradeRequest);

        Session clientSession = clientConnectFuture.get(Defaults.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);

        // Create string that is larger than default size of 64k
        // but smaller than maxMessageSize of 100k
        byte buf[] = new byte[80 * 1024];
        Arrays.fill(buf, (byte) 'x');
        String outgoingMessage = StringUtil.toUTF8String(buf, 0, buf.length);

        clientSession.getRemote().sendStringByFuture(outgoingMessage);

        String incomingMessage = clientEndpoint.messageQueue.poll(5, TimeUnit.SECONDS);
        assertThat("Message received", incomingMessage, is(outgoingMessage));
        clientSession.close();
    }

    @Test
    public void testParameterMap(TestInfo testInfo) throws Exception
    {
        TrackingEndpoint clientEndpoint = new TrackingEndpoint(testInfo);
        URI wsUri = server.getUntrustedWsUri(this.getClass(), testInfo).resolve("?snack=cashews&amount=handful&brand=off");
        assertThat("wsUri has query", wsUri.getQuery(), notNullValue());
        Future<Session> clientConnectFuture = client.connect(clientEndpoint, wsUri);

        Session clientSession = clientConnectFuture.get(Defaults.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);

        UpgradeRequest req = clientSession.getUpgradeRequest();
        assertThat("Upgrade Request", req, notNullValue());

        Map<String, List<String>> parameterMap = req.getParameterMap();
        assertThat("Parameter Map", parameterMap, notNullValue());

        assertThat("Parameter[snack]", parameterMap.get("snack"), is(Arrays.asList(new String[]{"cashews"})));
        assertThat("Parameter[amount]", parameterMap.get("amount"), is(Arrays.asList(new String[]{"handful"})));
        assertThat("Parameter[brand]", parameterMap.get("brand"), is(Arrays.asList(new String[]{"off"})));
        assertThat("Parameter[cost]", parameterMap.get("cost"), nullValue());
    }
}
