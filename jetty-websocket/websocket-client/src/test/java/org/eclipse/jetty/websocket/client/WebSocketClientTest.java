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

package org.eclipse.jetty.websocket.client;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThat;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.websocket.api.BatchMode;
import org.eclipse.jetty.websocket.api.RemoteEndpoint;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.UpgradeRequest;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.common.WebSocketSession;
import org.eclipse.jetty.websocket.common.frames.TextFrame;
import org.eclipse.jetty.websocket.common.io.FutureWriteCallback;
import org.eclipse.jetty.websocket.common.test.BlockheadConnection;
import org.eclipse.jetty.websocket.common.test.BlockheadServer;
import org.eclipse.jetty.websocket.common.test.Timeouts;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

public class WebSocketClientTest
{
    private static BlockheadServer server;
    private WebSocketClient client;

    @Before
    public void startClient() throws Exception
    {
        client = new WebSocketClient();
        client.start();
    }

    @BeforeClass
    public static void startServer() throws Exception
    {
        server = new BlockheadServer();
        server.getPolicy().setMaxTextMessageSize(200 * 1024);
        server.getPolicy().setMaxBinaryMessageSize(200 * 1024);
        server.start();
    }

    @After
    public void stopClient() throws Exception
    {
        client.stop();
    }

    @AfterClass
    public static void stopServer() throws Exception
    {
        server.stop();
    }

    @Test(expected = IllegalArgumentException.class)
    public void testAddExtension_NotInstalled() throws Exception
    {
        JettyTrackingSocket cliSock = new JettyTrackingSocket();

        client.getPolicy().setIdleTimeout(10000);

        URI wsUri = server.getWsUri();
        ClientUpgradeRequest request = new ClientUpgradeRequest();
        request.setSubProtocols("echo");
        request.addExtensions("x-bad");

        // Should trigger failure on bad extension
        client.connect(cliSock,wsUri,request);
    }

    @Test
    public void testBasicEcho_FromClient() throws Exception
    {
        JettyTrackingSocket cliSock = new JettyTrackingSocket();

        client.getPolicy().setIdleTimeout(10000);

        // Hook into server connection creation
        CompletableFuture<BlockheadConnection> serverConnFut = new CompletableFuture<>();
        server.addConnectFuture(serverConnFut);

        URI wsUri = server.getWsUri();
        ClientUpgradeRequest request = new ClientUpgradeRequest();
        request.setSubProtocols("echo");
        Future<Session> future = client.connect(cliSock,wsUri,request);

        try (BlockheadConnection serverConn = serverConnFut.get(Timeouts.CONNECT, Timeouts.CONNECT_UNIT))
        {
            // Setup echo of frames on server side
            serverConn.setIncomingFrameConsumer((frame)->{
                WebSocketFrame copy = WebSocketFrame.copy(frame);
                copy.setMask(null); // strip client mask (if present)
                serverConn.write(copy);
            });

            Session sess = future.get(30,TimeUnit.SECONDS);
            assertThat("Session",sess,notNullValue());
            assertThat("Session.open",sess.isOpen(),is(true));
            assertThat("Session.upgradeRequest",sess.getUpgradeRequest(),notNullValue());
            assertThat("Session.upgradeResponse",sess.getUpgradeResponse(),notNullValue());

            cliSock.assertWasOpened();
            cliSock.assertNotClosed();

            Collection<WebSocketSession> sessions = client.getOpenSessions();
            assertThat("client.connectionManager.sessions.size",sessions.size(),is(1));

            RemoteEndpoint remote = cliSock.getSession().getRemote();
            remote.sendStringByFuture("Hello World!");
            if (remote.getBatchMode() == BatchMode.ON)
                remote.flush();

            // wait for response from server
            String received = cliSock.messageQueue.poll(Timeouts.POLL_EVENT, Timeouts.POLL_EVENT_UNIT);
            assertThat("Message", received, containsString("Hello World"));
        }
    }

    @Test
    public void testBasicEcho_UsingCallback() throws Exception
    {
        client.setMaxIdleTimeout(160000);
        JettyTrackingSocket cliSock = new JettyTrackingSocket();

        // Hook into server connection creation
        CompletableFuture<BlockheadConnection> serverConnFut = new CompletableFuture<>();
        server.addConnectFuture(serverConnFut);

        URI wsUri = server.getWsUri();
        ClientUpgradeRequest request = new ClientUpgradeRequest();
        request.setSubProtocols("echo");
        Future<Session> future = client.connect(cliSock,wsUri,request);

        try (BlockheadConnection serverConn = serverConnFut.get(Timeouts.CONNECT, Timeouts.CONNECT_UNIT))
        {
            Session sess = future.get(30, TimeUnit.SECONDS);
            assertThat("Session", sess, notNullValue());
            assertThat("Session.open", sess.isOpen(), is(true));
            assertThat("Session.upgradeRequest", sess.getUpgradeRequest(), notNullValue());
            assertThat("Session.upgradeResponse", sess.getUpgradeResponse(), notNullValue());

            cliSock.assertWasOpened();
            cliSock.assertNotClosed();

            Collection<WebSocketSession> sessions = client.getBeans(WebSocketSession.class);
            assertThat("client.connectionManager.sessions.size", sessions.size(), is(1));

            FutureWriteCallback callback = new FutureWriteCallback();

            cliSock.getSession().getRemote().sendString("Hello World!", callback);
            callback.get(1, TimeUnit.SECONDS);
        }
    }

    @Test
    public void testBasicEcho_FromServer() throws Exception
    {
        // Hook into server connection creation
        CompletableFuture<BlockheadConnection> serverConnFut = new CompletableFuture<>();
        server.addConnectFuture(serverConnFut);

        JettyTrackingSocket wsocket = new JettyTrackingSocket();
        Future<Session> future = client.connect(wsocket,server.getWsUri());

        try (BlockheadConnection serverConn = serverConnFut.get(Timeouts.CONNECT, Timeouts.CONNECT_UNIT))
        {
            // Validate connect
            Session sess = future.get(30, TimeUnit.SECONDS);
            assertThat("Session", sess, notNullValue());
            assertThat("Session.open", sess.isOpen(), is(true));
            assertThat("Session.upgradeRequest", sess.getUpgradeRequest(), notNullValue());
            assertThat("Session.upgradeResponse", sess.getUpgradeResponse(), notNullValue());

            // Have server send initial message
            serverConn.write(new TextFrame().setPayload("Hello World"));

            // Verify connect
            future.get(30, TimeUnit.SECONDS);
            wsocket.assertWasOpened();

            String received = wsocket.messageQueue.poll(Timeouts.POLL_EVENT, Timeouts.POLL_EVENT_UNIT);
            assertThat("Message", received, containsString("Hello World"));
        }
    }

    @Test
    public void testLocalRemoteAddress() throws Exception
    {
        JettyTrackingSocket wsocket = new JettyTrackingSocket();

        URI wsUri = server.getWsUri();
        Future<Session> future = client.connect(wsocket,wsUri);

        future.get(30,TimeUnit.SECONDS);

        Assert.assertTrue(wsocket.openLatch.await(1,TimeUnit.SECONDS));

        InetSocketAddress local = wsocket.getSession().getLocalAddress();
        InetSocketAddress remote = wsocket.getSession().getRemoteAddress();

        assertThat("Local Socket Address",local,notNullValue());
        assertThat("Remote Socket Address",remote,notNullValue());

        // Hard to validate (in a portable unit test) the local address that was used/bound in the low level Jetty Endpoint
        assertThat("Local Socket Address / Host",local.getAddress().getHostAddress(),notNullValue());
        assertThat("Local Socket Address / Port",local.getPort(),greaterThan(0));

        String uriHostAddress = InetAddress.getByName(wsUri.getHost()).getHostAddress();
        assertThat("Remote Socket Address / Host",remote.getAddress().getHostAddress(),is(uriHostAddress));
        assertThat("Remote Socket Address / Port",remote.getPort(),greaterThan(0));
    }

    /**
     * Ensure that <code>@WebSocket(maxTextMessageSize = 100*1024)</code> behaves as expected.
     * 
     * @throws Exception
     *             on test failure
     */
    @Test
    public void testMaxMessageSize() throws Exception
    {
        MaxMessageSocket wsocket = new MaxMessageSocket();

        // Hook into server connection creation
        CompletableFuture<BlockheadConnection> serverConnFut = new CompletableFuture<>();
        server.addConnectFuture(serverConnFut);

        URI wsUri = server.getWsUri();
        Future<Session> future = client.connect(wsocket,wsUri);

        try (BlockheadConnection serverConn = serverConnFut.get(Timeouts.CONNECT, Timeouts.CONNECT_UNIT))
        {
            // Setup echo of frames on server side
            serverConn.setIncomingFrameConsumer((frame)->{
                WebSocketFrame copy = WebSocketFrame.copy(frame);
                copy.setMask(null); // strip client mask (if present)
                serverConn.write(copy);
            });

            wsocket.awaitConnect(1,TimeUnit.SECONDS);

            Session sess = future.get(30,TimeUnit.SECONDS);
            assertThat("Session",sess,notNullValue());
            assertThat("Session.open",sess.isOpen(),is(true));

            // Create string that is larger than default size of 64k
            // but smaller than maxMessageSize of 100k
            byte buf[] = new byte[80 * 1024];
            Arrays.fill(buf,(byte)'x');
            String msg = StringUtil.toUTF8String(buf,0,buf.length);

            wsocket.getSession().getRemote().sendStringByFuture(msg);

            // wait for response from server
            wsocket.waitForMessage(1, TimeUnit.SECONDS);

            wsocket.assertMessage(msg);

            Assert.assertTrue(wsocket.dataLatch.await(2, TimeUnit.SECONDS));
        }
    }

    @Test
    public void testParameterMap() throws Exception
    {
        JettyTrackingSocket wsocket = new JettyTrackingSocket();

        URI wsUri = server.getWsUri().resolve("/test?snack=cashews&amount=handful&brand=off");
        Future<Session> future = client.connect(wsocket,wsUri);

        future.get(30,TimeUnit.SECONDS);

        Assert.assertTrue(wsocket.openLatch.await(1,TimeUnit.SECONDS));

        Session session = wsocket.getSession();
        UpgradeRequest req = session.getUpgradeRequest();
        assertThat("Upgrade Request",req,notNullValue());

        Map<String, List<String>> parameterMap = req.getParameterMap();
        assertThat("Parameter Map",parameterMap,notNullValue());

        assertThat("Parameter[snack]",parameterMap.get("snack"),is(Arrays.asList(new String[] { "cashews" })));
        assertThat("Parameter[amount]",parameterMap.get("amount"),is(Arrays.asList(new String[] { "handful" })));
        assertThat("Parameter[brand]",parameterMap.get("brand"),is(Arrays.asList(new String[] { "off" })));

        assertThat("Parameter[cost]",parameterMap.get("cost"),nullValue());
    }
}
