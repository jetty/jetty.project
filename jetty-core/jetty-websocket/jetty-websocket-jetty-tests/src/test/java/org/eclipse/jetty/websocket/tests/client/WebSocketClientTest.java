//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.tests.client;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.UpgradeRequest;
import org.eclipse.jetty.websocket.api.util.WSURI;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeHandler;
import org.eclipse.jetty.websocket.tests.AnnoMaxMessageEndpoint;
import org.eclipse.jetty.websocket.tests.CloseTrackingEndpoint;
import org.eclipse.jetty.websocket.tests.ConnectMessageEndpoint;
import org.eclipse.jetty.websocket.tests.EchoSocket;
import org.eclipse.jetty.websocket.tests.ParamsEndpoint;
import org.eclipse.jetty.websocket.tests.util.FutureCallback;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class WebSocketClientTest
{
    private Server server;
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
        server = new Server();

        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        server.addConnector(connector);

        ContextHandler context = new ContextHandler("/");

        WebSocketUpgradeHandler wsHandler = WebSocketUpgradeHandler.from(server, context);
        context.setHandler(wsHandler);
        wsHandler.configure(container ->
        {
            container.setIdleTimeout(Duration.ofSeconds(10));
            container.addMapping("/echo", (upgradeRequest, upgradeResponse, callback) ->
            {
                if (upgradeRequest.hasSubProtocol("echo"))
                    upgradeResponse.setAcceptedSubProtocol("echo");
                return new EchoSocket();
            });
            container.addMapping("/anno-max-message", (rq, rs, cb) -> new AnnoMaxMessageEndpoint());
            container.addMapping("/connect-msg", (rq, rs, cb) -> new ConnectMessageEndpoint());
            container.addMapping("/get-params", (rq, rs, cb) -> new ParamsEndpoint());
        });

        server.setHandler(context);
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
    public void testAddExtensionNotInstalled() throws Exception
    {
        CloseTrackingEndpoint cliSock = new CloseTrackingEndpoint();

        client.setIdleTimeout(Duration.ofSeconds(10));

        URI wsUri = WSURI.toWebsocket(server.getURI().resolve("/echo"));
        ClientUpgradeRequest request = new ClientUpgradeRequest();
        request.setSubProtocols("echo");
        request.addExtensions("x-bad");

        assertThrows(IllegalArgumentException.class, () ->
        {
            // Should trigger failure on bad extension
            client.connect(cliSock, wsUri, request);
        });
    }

    @Test
    public void testBasicEchoFromClient() throws Exception
    {
        CloseTrackingEndpoint cliSock = new CloseTrackingEndpoint();

        client.setIdleTimeout(Duration.ofSeconds(10));

        URI wsUri = WSURI.toWebsocket(server.getURI().resolve("/echo"));
        ClientUpgradeRequest request = new ClientUpgradeRequest();
        request.setSubProtocols("echo");
        Future<Session> future = client.connect(cliSock, wsUri, request);

        try (Session sess = future.get(30, TimeUnit.SECONDS))
        {
            assertThat("Session", sess, notNullValue());
            assertThat("Session.open", sess.isOpen(), is(true));
            assertThat("Session.upgradeRequest", sess.getUpgradeRequest(), notNullValue());
            assertThat("Session.upgradeResponse", sess.getUpgradeResponse(), notNullValue());

            Collection<Session> sessions = client.getOpenSessions();
            assertThat("client.sessions.size", sessions.size(), is(1));

            cliSock.getSession().sendText("Hello World!", Callback.NOOP);

            // wait for response from server
            String received = cliSock.messageQueue.poll(5, TimeUnit.SECONDS);
            assertThat("Message", received, containsString("Hello World"));
        }
    }

    @Test
    public void testBasicEchoPartialUsageFromClient() throws Exception
    {
        CloseTrackingEndpoint cliSock = new CloseTrackingEndpoint();

        client.setIdleTimeout(Duration.ofSeconds(10));

        URI wsUri = WSURI.toWebsocket(server.getURI().resolve("/echo"));
        ClientUpgradeRequest request = new ClientUpgradeRequest();
        request.setSubProtocols("echo");
        Future<Session> future = client.connect(cliSock, wsUri, request);

        try (Session sess = future.get(30, TimeUnit.SECONDS))
        {
            assertThat("Session", sess, notNullValue());
            assertThat("Session.open", sess.isOpen(), is(true));
            assertThat("Session.upgradeRequest", sess.getUpgradeRequest(), notNullValue());
            assertThat("Session.upgradeResponse", sess.getUpgradeResponse(), notNullValue());

            Collection<Session> sessions = client.getOpenSessions();
            assertThat("client.sessions.size", sessions.size(), is(1));

            cliSock.getSession().sendPartialText("Hello", false, Callback.NOOP);
            cliSock.getSession().sendPartialText(" ", false, Callback.NOOP);
            cliSock.getSession().sendPartialText("World", true, Callback.NOOP);

            // wait for response from server
            String received = cliSock.messageQueue.poll(5, TimeUnit.SECONDS);
            assertThat("Message", received, containsString("Hello World"));
        }
    }

    @Test
    public void testBasicEchoPartialTextWithPartialBinaryFromClient() throws Exception
    {
        CloseTrackingEndpoint cliSock = new CloseTrackingEndpoint();

        client.setIdleTimeout(Duration.ofSeconds(10));

        URI wsUri = WSURI.toWebsocket(server.getURI().resolve("/echo"));
        ClientUpgradeRequest request = new ClientUpgradeRequest();
        request.setSubProtocols("echo");
        Future<Session> future = client.connect(cliSock, wsUri, request);

        try (Session sess = future.get(30, TimeUnit.SECONDS))
        {
            assertThat("Session", sess, notNullValue());
            assertThat("Session.open", sess.isOpen(), is(true));
            assertThat("Session.upgradeRequest", sess.getUpgradeRequest(), notNullValue());
            assertThat("Session.upgradeResponse", sess.getUpgradeResponse(), notNullValue());

            Collection<Session> sessions = client.getOpenSessions();
            assertThat("client.sessions.size", sessions.size(), is(1));

            cliSock.getSession().sendPartialText("Hello", false, Callback.NOOP);
            cliSock.getSession().sendPartialText(" ", false, Callback.NOOP);
            cliSock.getSession().sendPartialText("World", true, Callback.NOOP);

            String[] parts = {
                "The difference between the right word ",
                "and the almost right word is the difference ",
                "between lightning and a lightning bug."
            };

            Session session2 = cliSock.getSession();
            ByteBuffer b2 = BufferUtil.toBuffer(parts[0]);
            session2.sendPartialBinary(b2, false, Callback.NOOP);
            Session session1 = cliSock.getSession();
            ByteBuffer b1 = BufferUtil.toBuffer(parts[1]);
            session1.sendPartialBinary(b1, false, Callback.NOOP);
            Session session = cliSock.getSession();
            ByteBuffer b = BufferUtil.toBuffer(parts[2]);
            session.sendPartialBinary(b, true, Callback.NOOP);

            // wait for response from server
            String received = cliSock.messageQueue.poll(5, TimeUnit.SECONDS);
            assertThat("Message", received, containsString("Hello World"));

            ByteBuffer bufReceived = cliSock.binaryMessageQueue.poll(5, TimeUnit.SECONDS);
            received = BufferUtil.toUTF8String(bufReceived.slice());
            assertThat("Message", received, containsString(parts[0] + parts[1] + parts[2]));
        }
    }

    @Test
    public void testBasicEchoUsingCallback() throws Exception
    {
        CloseTrackingEndpoint cliSock = new CloseTrackingEndpoint();

        client.setIdleTimeout(Duration.ofSeconds(10));

        URI wsUri = WSURI.toWebsocket(server.getURI().resolve("/echo"));
        ClientUpgradeRequest request = new ClientUpgradeRequest();
        request.setSubProtocols("echo");
        Future<Session> future = client.connect(cliSock, wsUri, request);

        try (Session sess = future.get(5, TimeUnit.SECONDS))
        {
            assertThat("Session", sess, notNullValue());
            assertThat("Session.open", sess.isOpen(), is(true));
            assertThat("Session.upgradeRequest", sess.getUpgradeRequest(), notNullValue());
            assertThat("Session.upgradeResponse", sess.getUpgradeResponse(), notNullValue());

            Collection<Session> sessions = client.getOpenSessions();
            assertThat("client.sessions.size", sessions.size(), is(1));

            FutureCallback callback = new FutureCallback();

            cliSock.getSession().sendText("Hello World!", callback);
            callback.get(5, TimeUnit.SECONDS);

            // wait for response from server
            String received = cliSock.messageQueue.poll(5, TimeUnit.SECONDS);
            assertThat("Message", received, containsString("Hello World"));
        }
    }

    @Test
    public void testBasicEchoFromServer() throws Exception
    {
        CloseTrackingEndpoint cliSock = new CloseTrackingEndpoint();

        client.setIdleTimeout(Duration.ofSeconds(10));

        URI wsUri = WSURI.toWebsocket(server.getURI().resolve("/connect-msg"));
        Future<Session> future = client.connect(cliSock, wsUri);

        try (Session sess = future.get(5, TimeUnit.SECONDS))
        {
            // Validate connect
            assertThat("Session", sess, notNullValue());
            assertThat("Session.open", sess.isOpen(), is(true));
            assertThat("Session.upgradeRequest", sess.getUpgradeRequest(), notNullValue());
            assertThat("Session.upgradeResponse", sess.getUpgradeResponse(), notNullValue());

            // wait for message from server
            String received = cliSock.messageQueue.poll(5, TimeUnit.SECONDS);
            assertThat("Message", received, containsString("Greeting from onOpen"));
        }
    }

    @Test
    public void testLocalRemoteAddress() throws Exception
    {
        CloseTrackingEndpoint cliSock = new CloseTrackingEndpoint();

        client.setIdleTimeout(Duration.ofSeconds(10));

        URI wsUri = WSURI.toWebsocket(server.getURI().resolve("/echo"));
        ClientUpgradeRequest request = new ClientUpgradeRequest();
        request.setSubProtocols("echo");
        Future<Session> future = client.connect(cliSock, wsUri, request);

        try (Session sess = future.get(5, TimeUnit.SECONDS))
        {
            Assertions.assertTrue(cliSock.connectLatch.await(1, TimeUnit.SECONDS));

            InetSocketAddress local = (InetSocketAddress)cliSock.getSession().getLocalSocketAddress();
            InetSocketAddress remote = (InetSocketAddress)cliSock.getSession().getRemoteSocketAddress();

            assertThat("Local Socket Address", local, notNullValue());
            assertThat("Remote Socket Address", remote, notNullValue());

            // Hard to validate (in a portable unit test) the local address that was used/bound in the low level Jetty Endpoint
            assertThat("Local Socket Address / Host", local.getAddress().getHostAddress(), notNullValue());
            assertThat("Local Socket Address / Port", local.getPort(), greaterThan(0));

            String uriHostAddress = InetAddress.getByName(wsUri.getHost()).getHostAddress();
            assertThat("Remote Socket Address / Host", remote.getAddress().getHostAddress(), is(uriHostAddress));
            assertThat("Remote Socket Address / Port", remote.getPort(), greaterThan(0));
        }
    }

    /**
     * Ensure that <code>@WebSocket(maxTextMessageSize = 100*1024)</code> behaves as expected.
     *
     * @throws Exception on test failure
     */
    @Test
    public void testMaxMessageSize() throws Exception
    {
        CloseTrackingEndpoint cliSock = new CloseTrackingEndpoint();

        client.setMaxTextMessageSize(100 * 1024);
        client.setIdleTimeout(Duration.ofSeconds(10));

        URI wsUri = WSURI.toWebsocket(server.getURI().resolve("/anno-max-message"));
        Future<Session> future = client.connect(cliSock, wsUri);

        try (Session sess = future.get(5, TimeUnit.SECONDS))
        {
            assertThat("Session", sess, notNullValue());
            assertThat("Session.open", sess.isOpen(), is(true));

            // Create string that is larger than default size of 64k
            // but smaller than maxMessageSize of 100k
            int size = 80 * 1024;
            byte[] buf = new byte[size];
            Arrays.fill(buf, (byte)'x');
            String msg = StringUtil.toUTF8String(buf, 0, buf.length);

            sess.sendText(msg, Callback.NOOP);

            // wait for message from server
            String received = cliSock.messageQueue.poll(5, TimeUnit.SECONDS);
            assertThat("Message", received.length(), is(size));
        }
    }

    @Test
    public void testParameterMap() throws Exception
    {
        CloseTrackingEndpoint cliSock = new CloseTrackingEndpoint();

        client.setMaxTextMessageSize(100 * 1024);
        client.setIdleTimeout(Duration.ofSeconds(10));

        URI wsUri = WSURI.toWebsocket(server.getURI().resolve("/get-params?snack=cashews&amount=handful&brand=off"));
        ClientUpgradeRequest request = new ClientUpgradeRequest();
        Future<Session> future = client.connect(cliSock, wsUri, request);

        try (Session sess = future.get(5, TimeUnit.SECONDS))
        {
            UpgradeRequest req = sess.getUpgradeRequest();
            assertThat("Upgrade Request", req, notNullValue());

            Map<String, List<String>> parameterMap = req.getParameterMap();
            assertThat("Parameter Map", parameterMap, notNullValue());

            assertThat("Parameter[snack]", parameterMap.get("snack"), is(Arrays.asList(new String[]{"cashews"})));
            assertThat("Parameter[amount]", parameterMap.get("amount"), is(Arrays.asList(new String[]{"handful"})));
            assertThat("Parameter[brand]", parameterMap.get("brand"), is(Arrays.asList(new String[]{"off"})));

            assertThat("Parameter[cost]", parameterMap.get("cost"), nullValue());

            // wait for message from server indicating what it sees
            String received = cliSock.messageQueue.poll(5, TimeUnit.SECONDS);
            assertThat("Parameter[snack]", received, containsString("Params[snack]=[cashews]"));
            assertThat("Parameter[amount]", received, containsString("Params[amount]=[handful]"));
            assertThat("Parameter[brand]", received, containsString("Params[brand]=[off]"));
            assertThat("Parameter[cost]", received, not(containsString("Params[cost]=")));
        }
    }
}
