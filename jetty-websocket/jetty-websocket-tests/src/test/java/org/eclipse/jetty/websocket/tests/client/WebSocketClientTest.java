//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThat;

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

import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.common.WebSocketSessionImpl;
import org.eclipse.jetty.websocket.core.handshake.UpgradeRequest;
import org.eclipse.jetty.websocket.tests.Defaults;
import org.eclipse.jetty.websocket.tests.TrackingEndpoint;
import org.eclipse.jetty.websocket.tests.UntrustedWSEndpoint;
import org.eclipse.jetty.websocket.tests.UntrustedWSServer;
import org.eclipse.jetty.websocket.tests.UntrustedWSSession;
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
    
    private UntrustedWSServer server;
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
        server = new UntrustedWSServer();
        server.start();
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
        
        URI wsUri = server.getWsUri();
        ClientUpgradeRequest request = new ClientUpgradeRequest();
        request.setSubProtocols("echo");
        request.addExtensions("x-bad"); // extension that doesn't exist
        
        // Should trigger failure on bad extension
        expectedException.expect(IllegalArgumentException.class);
        expectedException.expectMessage(containsString("x-bad"));
        client.connect(clientEndpoint, wsUri, request);
    }
    
    @Test
    public void testBasicEcho() throws IOException, InterruptedException, ExecutionException, TimeoutException
    {
        // Set client timeout
        final int timeout = 1000;
        client.setMaxIdleTimeout(timeout);
        
        URI wsUri = server.getUntrustedWsUri(this.getClass(), testname);
        CompletableFuture<UntrustedWSSession> serverSessionFut = new CompletableFuture<>();
        server.registerOnOpenFuture(wsUri, serverSessionFut);
        
        // Client connects
        TrackingEndpoint clientEndpoint = new TrackingEndpoint(testname.getMethodName());
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
        Collection<WebSocketSessionImpl> sessions = client.getBeans(WebSocketSessionImpl.class);
        Assert.assertThat("client.beans[session].size", sessions.size(), is(1));
        
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
        serverEndpoint.assertCloseStatus("Server", StatusCode.NORMAL, containsString("Normal Close"));
        
        // client triggers close event on client ws-endpoint
        clientEndpoint.awaitCloseEvent("Client");
        clientEndpoint.assertCloseStatus("Client", StatusCode.NORMAL, containsString("Normal Close"));
    }
    
    @Test
    public void testBasicEcho_UsingCallback() throws Exception
    {
        client.setMaxIdleTimeout(160000);
        TrackingEndpoint clientEndpoint = new TrackingEndpoint(testname.getMethodName());
        URI wsUri = server.getUntrustedWsUri(this.getClass(), testname);
        ClientUpgradeRequest request = new ClientUpgradeRequest();
        request.setSubProtocols("echo");
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
        URI wsUri = server.getUntrustedWsUri(this.getClass(), testname);
        Future<Session> clientConnectFuture = client.connect(clientEndpoint, wsUri);
        
        Session clientSession = clientConnectFuture.get(Defaults.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        
        InetSocketAddress local = clientSession.getLocalAddress();
        InetSocketAddress remote = clientSession.getRemoteAddress();
        
        Assert.assertThat("Local Socket Address", local, notNullValue());
        Assert.assertThat("Remote Socket Address", remote, notNullValue());
        
        // Hard to validate (in a portable unit test) the local address that was used/bound in the low level Jetty Endpoint
        Assert.assertThat("Local Socket Address / Host", local.getAddress().getHostAddress(), notNullValue());
        Assert.assertThat("Local Socket Address / Port", local.getPort(), greaterThan(0));
        
        Assert.assertThat("Remote Socket Address / Host", remote.getAddress().getHostAddress(), is(wsUri.getHost()));
        Assert.assertThat("Remote Socket Address / Port", remote.getPort(), greaterThan(0));
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
        URI wsUri = server.getUntrustedWsUri(this.getClass(), testname);
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
    public void testParameterMap() throws Exception
    {
        TrackingEndpoint clientEndpoint = new TrackingEndpoint(testname.getMethodName());
        URI wsUri = server.getUntrustedWsUri(this.getClass(), testname).resolve("?snack=cashews&amount=handful&brand=off");
        assertThat("wsUri has query", wsUri.getQuery(), notNullValue());
        Future<Session> clientConnectFuture = client.connect(clientEndpoint, wsUri);
        
        Session clientSession = clientConnectFuture.get(Defaults.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        
        UpgradeRequest req = clientSession.getUpgradeRequest();
        Assert.assertThat("Upgrade Request", req, notNullValue());
        
        Map<String, List<String>> parameterMap = req.getParameterMap();
        Assert.assertThat("Parameter Map", parameterMap, notNullValue());
        
        Assert.assertThat("Parameter[snack]", parameterMap.get("snack"), is(Arrays.asList(new String[]{"cashews"})));
        Assert.assertThat("Parameter[amount]", parameterMap.get("amount"), is(Arrays.asList(new String[]{"handful"})));
        Assert.assertThat("Parameter[brand]", parameterMap.get("brand"), is(Arrays.asList(new String[]{"off"})));
        Assert.assertThat("Parameter[cost]", parameterMap.get("cost"), nullValue());
    }
}
