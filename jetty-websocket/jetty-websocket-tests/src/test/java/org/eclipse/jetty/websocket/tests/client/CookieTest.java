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
import static org.junit.Assert.assertThat;

import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.util.Collections;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WSURI;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.common.HandshakeRequest;
import org.eclipse.jetty.websocket.tests.Defaults;
import org.eclipse.jetty.websocket.tests.LocalServer;
import org.eclipse.jetty.websocket.tests.TrackingEndpoint;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

public class CookieTest
{
    @WebSocket
    public static class EchoCookiesSocket
    {
        private final HandshakeRequest handshakeRequest;

        public EchoCookiesSocket(HandshakeRequest handshakeRequest)
        {
            this.handshakeRequest = handshakeRequest;
        }

        @OnWebSocketConnect
        public void onOpen(Session session)
        {
            StringBuilder resp = new StringBuilder();
            handshakeRequest.getCookies().forEach((cookie) -> {
                resp.append(cookie.toString()).append("\n");
            });
            session.getRemote().sendText(resp.toString(), Callback.NOOP);
        }
    }

    @Rule
    public TestName testname = new TestName();

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
        server.registerWebSocket("/cookies", (req, resp) -> new EchoCookiesSocket(req));
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
    public void testViaCookieManager() throws Exception
    {
        TrackingEndpoint clientSocket = new TrackingEndpoint(testname.getMethodName());

        // Setup client
        CookieManager cookieMgr = new CookieManager();
        client.setCookieStore(cookieMgr.getCookieStore());
        HttpCookie cookie = new HttpCookie("hello", "world");
        cookie.setPath("/");
        cookie.setVersion(0);
        cookie.setMaxAge(100000);
        cookieMgr.getCookieStore().add(server.getServerUri(), cookie);

        cookie = new HttpCookie("foo", "bar is the word");
        cookie.setPath("/");
        cookie.setMaxAge(100000);
        cookieMgr.getCookieStore().add(server.getServerUri(), cookie);

        // Client connects
        URI wsUri = WSURI.toWebsocket(server.getServerUri()).resolve("/cookies");
        Future<Session> clientConnectFuture = client.connect(clientSocket, wsUri);
        clientConnectFuture.get(Defaults.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);

        // client confirms upgrade and receipt of frame
        String responseMessage = clientSocket.messageQueue.poll(1, TimeUnit.SECONDS);
        assertThat("Cookies seen at server side", responseMessage, containsString("hello=world"));
        assertThat("Cookies seen at server side", responseMessage, containsString("foo=bar is the word"));
    }

    @Test
    public void testViaServletUpgradeRequest() throws Exception
    {
        TrackingEndpoint clientSocket = new TrackingEndpoint(testname.getMethodName());

        // Setup client
        HttpCookie cookie = new HttpCookie("hello", "world");
        cookie.setPath("/");
        cookie.setMaxAge(100000);

        ClientUpgradeRequest request = new ClientUpgradeRequest();
        request.setCookies(Collections.singletonList(cookie));

        // Client connects
        URI wsUri = WSURI.toWebsocket(server.getServerUri()).resolve("/cookies");
        Future<Session> clientConnectFuture = client.connect(clientSocket, wsUri, request);
        clientConnectFuture.get(Defaults.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);

        // client confirms upgrade and receipt of frame
        String responseMessage = clientSocket.messageQueue.poll(1, TimeUnit.SECONDS);
        assertThat("Cookies seen at server side", responseMessage, containsString("hello=world"));
    }
}
