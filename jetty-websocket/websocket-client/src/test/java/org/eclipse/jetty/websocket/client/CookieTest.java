//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import java.net.CookieManager;
import java.net.HttpCookie;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.toolchain.test.EventQueue;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.WebSocketAdapter;
import org.eclipse.jetty.websocket.api.util.QuoteUtil;
import org.eclipse.jetty.websocket.common.frames.TextFrame;
import org.eclipse.jetty.websocket.common.test.BlockheadServer;
import org.eclipse.jetty.websocket.common.test.IBlockheadServerConnection;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class CookieTest
{
    private static final Logger LOG = Log.getLogger(CookieTest.class);

    public static class CookieTrackingSocket extends WebSocketAdapter
    {
        public EventQueue<String> messageQueue = new EventQueue<>();
        public EventQueue<Throwable> errorQueue = new EventQueue<>();
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
            System.err.printf("onTEXT - %s%n",message);
            messageQueue.add(message);
        }

        @Override
        public void onWebSocketError(Throwable cause)
        {
            System.err.printf("onERROR - %s%n",cause);
            errorQueue.add(cause);
        }

        public void awaitOpen(int duration, TimeUnit unit) throws InterruptedException
        {
            assertTrue("Open Latch", openLatch.await(duration,unit));
        }
    }

    private WebSocketClient client;
    private BlockheadServer server;

    @Before
    public void startClient() throws Exception
    {
        client = new WebSocketClient();
        client.start();
    }

    @Before
    public void startServer() throws Exception
    {
        server = new BlockheadServer();
        server.start();
    }

    @After
    public void stopClient() throws Exception
    {
        if (client.isRunning())
        {
            client.stop();
        }
    }

    @After
    public void stopServer() throws Exception
    {
        server.stop();
    }

    @Test
    public void testViaCookieManager() throws Exception
    {
        // Setup client
        CookieManager cookieMgr = new CookieManager();
        client.setCookieStore(cookieMgr.getCookieStore());
        HttpCookie cookie = new HttpCookie("hello","world");
        cookie.setPath("/");
        cookie.setVersion(0);
        cookie.setMaxAge(100000);
        cookieMgr.getCookieStore().add(server.getWsUri(),cookie);
        
        cookie = new HttpCookie("foo","bar is the word");
        cookie.setPath("/");
        cookie.setMaxAge(100000);
        cookieMgr.getCookieStore().add(server.getWsUri(),cookie);

        // Client connects
        CookieTrackingSocket clientSocket = new CookieTrackingSocket();
        Future<Session> clientConnectFuture = client.connect(clientSocket,server.getWsUri());

        // Server accepts connect
        IBlockheadServerConnection serverConn = server.accept();

        // client confirms upgrade and receipt of frame
        String serverCookies = confirmClientUpgradeAndCookies(clientSocket,clientConnectFuture,serverConn);

        assertThat("Cookies seen at server side",serverCookies,containsString("hello=world"));
        assertThat("Cookies seen at server side",serverCookies,containsString("foo=bar is the word"));
    }
    
    @Test
    public void testViaServletUpgradeRequest() throws Exception
    {
        // Setup client
        HttpCookie cookie = new HttpCookie("hello","world");
        cookie.setPath("/");
        cookie.setMaxAge(100000);
        
        ClientUpgradeRequest request = new ClientUpgradeRequest();
        request.setCookies(Collections.singletonList(cookie));

        // Client connects
        CookieTrackingSocket clientSocket = new CookieTrackingSocket();
        Future<Session> clientConnectFuture = client.connect(clientSocket,server.getWsUri(),request);

        // Server accepts connect
        IBlockheadServerConnection serverConn = server.accept();

        // client confirms upgrade and receipt of frame
        String serverCookies = confirmClientUpgradeAndCookies(clientSocket,clientConnectFuture,serverConn);

        Assert.assertThat("Cookies seen at server side",serverCookies,containsString("hello=world"));
    }

    private String confirmClientUpgradeAndCookies(CookieTrackingSocket clientSocket, Future<Session> clientConnectFuture, IBlockheadServerConnection serverConn)
            throws Exception
    {
        // Server upgrades
        List<String> upgradeRequestLines = serverConn.upgrade();
        List<String> upgradeRequestCookies = serverConn.regexFind(upgradeRequestLines,"^Cookie: (.*)$");

        // Server responds with cookies it knows about
        TextFrame serverCookieFrame = new TextFrame();
        serverCookieFrame.setFin(true);
        serverCookieFrame.setPayload(QuoteUtil.join(upgradeRequestCookies,","));
        serverConn.write(serverCookieFrame);
        serverConn.flush();

        // Confirm client connect on future
        clientConnectFuture.get(10,TimeUnit.SECONDS);
        clientSocket.awaitOpen(2,TimeUnit.SECONDS);
    
        try
        {
            // Wait for client receipt of cookie frame via client websocket
            clientSocket.messageQueue.awaitEventCount(1, 3, TimeUnit.SECONDS);
        }
        catch (TimeoutException e)
        {
            e.printStackTrace(System.err);
            assertThat("Message Count", clientSocket.messageQueue.size(), is(1));
        }

        String cookies = clientSocket.messageQueue.poll();
        LOG.debug("Cookies seen at server: {}",cookies);
        
        // Server closes connection
        serverConn.close(StatusCode.NORMAL);
    
        return cookies;
    }
}
