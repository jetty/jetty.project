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

package org.eclipse.jetty.websocket.client;

import java.net.CookieManager;
import java.net.HttpCookie;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.WebSocketAdapter;
import org.eclipse.jetty.websocket.common.CloseInfo;
import org.eclipse.jetty.websocket.common.frames.TextFrame;
import org.eclipse.jetty.websocket.common.test.BlockheadConnection;
import org.eclipse.jetty.websocket.common.test.BlockheadServer;
import org.eclipse.jetty.websocket.common.test.Timeouts;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CookieTest
{
    private static final Logger LOG = Log.getLogger(CookieTest.class);

    public static class CookieTrackingSocket extends WebSocketAdapter
    {
        public LinkedBlockingQueue<String> messageQueue = new LinkedBlockingQueue<>();
        public LinkedBlockingQueue<Throwable> errorQueue = new LinkedBlockingQueue<>();
        private CountDownLatch openLatch = new CountDownLatch(1);

        @Override
        public void onWebSocketConnect(Session sess)
        {
            openLatch.countDown();
            super.onWebSocketConnect(sess);
        }

        @Override
        public void onWebSocketText(String message)
        {
            System.err.printf("onTEXT - %s%n", message);
            messageQueue.add(message);
        }

        @Override
        public void onWebSocketError(Throwable cause)
        {
            System.err.printf("onERROR - %s%n", cause);
            errorQueue.add(cause);
        }

        public void awaitOpen(int duration, TimeUnit unit) throws InterruptedException
        {
            assertTrue(openLatch.await(duration, unit), "Open Latch");
        }
    }

    private static BlockheadServer server;
    private WebSocketClient client;

    @BeforeEach
    public void startClient() throws Exception
    {
        client = new WebSocketClient();
        client.start();
    }

    @BeforeAll
    public static void startServer() throws Exception
    {
        server = new BlockheadServer();
        server.start();
    }

    @AfterEach
    public void stopClient() throws Exception
    {
        if (client.isRunning())
        {
            client.stop();
        }
    }

    @AfterAll
    public static void stopServer() throws Exception
    {
        server.stop();
    }

    @Test
    public void testViaCookieManager() throws Exception
    {
        // Setup client
        CookieManager cookieMgr = new CookieManager();
        client.setCookieStore(cookieMgr.getCookieStore());
        HttpCookie cookie = new HttpCookie("hello", "world");
        cookie.setPath("/");
        cookie.setVersion(0);
        cookie.setMaxAge(100000);
        cookieMgr.getCookieStore().add(server.getWsUri(), cookie);

        cookie = new HttpCookie("foo", "bar is the word");
        cookie.setPath("/");
        cookie.setMaxAge(100000);
        cookieMgr.getCookieStore().add(server.getWsUri(), cookie);

        // Hook into server connection creation
        CompletableFuture<BlockheadConnection> serverConnFut = new CompletableFuture<>();
        server.addConnectFuture(serverConnFut);

        // Client connects
        CookieTrackingSocket clientSocket = new CookieTrackingSocket();
        Future<Session> clientConnectFuture = client.connect(clientSocket, server.getWsUri());

        try (BlockheadConnection serverConn = serverConnFut.get(Timeouts.CONNECT, Timeouts.CONNECT_UNIT))
        {
            // client confirms upgrade and receipt of frame
            String serverCookies = confirmClientUpgradeAndCookies(clientSocket, clientConnectFuture, serverConn);

            assertThat("Cookies seen at server side", serverCookies, containsString("hello=world"));
            assertThat("Cookies seen at server side", serverCookies, containsString("foo=bar is the word"));
        }
    }

    @Test
    public void testViaServletUpgradeRequest() throws Exception
    {
        // Setup client
        HttpCookie cookie = new HttpCookie("hello", "world");
        cookie.setPath("/");
        cookie.setMaxAge(100000);

        ClientUpgradeRequest request = new ClientUpgradeRequest();
        request.setCookies(Collections.singletonList(cookie));

        // Hook into server connection creation
        CompletableFuture<BlockheadConnection> serverConnFut = new CompletableFuture<>();
        server.addConnectFuture(serverConnFut);

        // Client connects
        CookieTrackingSocket clientSocket = new CookieTrackingSocket();
        Future<Session> clientConnectFuture = client.connect(clientSocket, server.getWsUri(), request);

        try (BlockheadConnection serverConn = serverConnFut.get(Timeouts.CONNECT, Timeouts.CONNECT_UNIT))
        {
            // client confirms upgrade and receipt of frame
            String serverCookies = confirmClientUpgradeAndCookies(clientSocket, clientConnectFuture, serverConn);

            assertThat("Cookies seen at server side", serverCookies, containsString("hello=world"));
        }
    }

    private String confirmClientUpgradeAndCookies(CookieTrackingSocket clientSocket, Future<Session> clientConnectFuture, BlockheadConnection serverConn)
        throws Exception
    {
        // Server side upgrade information
        HttpFields upgradeRequestHeaders = serverConn.getUpgradeRequestHeaders();
        HttpField cookieField = upgradeRequestHeaders.getField(HttpHeader.COOKIE);

        // Server responds with cookies it knows about
        TextFrame serverCookieFrame = new TextFrame();
        serverCookieFrame.setFin(true);
        serverCookieFrame.setPayload(cookieField.getValue());
        serverConn.write(serverCookieFrame);

        // Confirm client connect on future
        Session session = clientConnectFuture.get(10, TimeUnit.SECONDS);
        assertTrue(session.getUpgradeResponse().isSuccess(), "UpgradeResponse.isSuccess()");
        clientSocket.awaitOpen(2, TimeUnit.SECONDS);

        // Wait for client receipt of cookie frame via client websocket
        String cookies = clientSocket.messageQueue.poll(Timeouts.POLL_EVENT, Timeouts.POLL_EVENT_UNIT);
        LOG.debug("Cookies seen at server: {}", cookies);

        // Server closes connection
        serverConn.write(new CloseInfo(StatusCode.NORMAL).asFrame());

        return cookies;
    }
}
