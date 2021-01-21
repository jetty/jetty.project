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

package org.eclipse.jetty.websocket.server;

import java.net.HttpCookie;
import java.net.URI;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.websocket.api.UpgradeRequest;
import org.eclipse.jetty.websocket.api.UpgradeResponse;
import org.eclipse.jetty.websocket.common.test.BlockheadClient;
import org.eclipse.jetty.websocket.common.test.BlockheadClientRequest;
import org.eclipse.jetty.websocket.common.test.BlockheadConnection;
import org.eclipse.jetty.websocket.common.test.Timeouts;
import org.eclipse.jetty.websocket.server.helper.EchoSocket;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeRequest;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeResponse;
import org.eclipse.jetty.websocket.servlet.WebSocketCreator;
import org.eclipse.jetty.websocket.servlet.WebSocketServlet;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

public class RequestHeadersTest
{

    private static class EchoCreator implements WebSocketCreator
    {
        private UpgradeRequest lastRequest;
        private UpgradeResponse lastResponse;
        private EchoSocket echoSocket = new EchoSocket();

        @Override
        public Object createWebSocket(ServletUpgradeRequest req, ServletUpgradeResponse resp)
        {
            this.lastRequest = req;
            this.lastResponse = resp;
            return echoSocket;
        }

        public UpgradeRequest getLastRequest()
        {
            return lastRequest;
        }

        @SuppressWarnings("unused")
        public UpgradeResponse getLastResponse()
        {
            return lastResponse;
        }
    }

    public static class EchoRequestServlet extends WebSocketServlet
    {
        private static final long serialVersionUID = -6575001979901924179L;
        private final WebSocketCreator creator;

        public EchoRequestServlet(WebSocketCreator creator)
        {
            this.creator = creator;
        }

        @Override
        public void configure(WebSocketServletFactory factory)
        {
            factory.setCreator(this.creator);
        }
    }

    private static BlockheadClient client;
    private static SimpleServletServer server;
    private static EchoCreator echoCreator;

    @BeforeAll
    public static void startServer() throws Exception
    {
        echoCreator = new EchoCreator();
        server = new SimpleServletServer(new EchoRequestServlet(echoCreator));
        server.start();
    }

    @AfterAll
    public static void stopServer()
    {
        server.stop();
    }

    @BeforeAll
    public static void startClient() throws Exception
    {
        client = new BlockheadClient();
        client.setIdleTimeout(TimeUnit.SECONDS.toMillis(2));
        client.start();
    }

    @AfterAll
    public static void stopClient() throws Exception
    {
        client.stop();
    }

    @Test
    public void testAccessRequestCookies() throws Exception
    {
        BlockheadClientRequest request = client.newWsRequest(server.getServerUri());
        request.idleTimeout(1, TimeUnit.SECONDS);
        request.header(HttpHeader.COOKIE, "fruit=Pear; type=Anjou");

        Future<BlockheadConnection> connFut = request.sendAsync();

        try (BlockheadConnection ignore = connFut.get(Timeouts.CONNECT, Timeouts.CONNECT_UNIT))
        {
            UpgradeRequest req = echoCreator.getLastRequest();
            assertThat("Last Request", req, notNullValue());
            List<HttpCookie> cookies = req.getCookies();
            assertThat("Request cookies", cookies, notNullValue());
            assertThat("Request cookies.size", cookies.size(), is(2));
            for (HttpCookie cookie : cookies)
            {
                assertThat("Cookie name", cookie.getName(), anyOf(is("fruit"), is("type")));
                assertThat("Cookie value", cookie.getValue(), anyOf(is("Pear"), is("Anjou")));
            }
        }
    }

    @Test
    public void testRequestURI() throws Exception
    {
        URI destUri = server.getServerUri().resolve("/?abc=x%20z&breakfast=bacon%26eggs&2*2%3d5=false");
        BlockheadClientRequest request = client.newWsRequest(destUri);
        request.idleTimeout(1, TimeUnit.SECONDS);

        Future<BlockheadConnection> connFut = request.sendAsync();

        try (BlockheadConnection ignore = connFut.get(Timeouts.CONNECT, Timeouts.CONNECT_UNIT))
        {
            UpgradeRequest req = echoCreator.getLastRequest();
            assertThat("Last Request", req, notNullValue());
            assertThat("Request.host", req.getHost(), is(server.getServerUri().getHost()));
            assertThat("Request.queryString", req.getQueryString(), is("abc=x%20z&breakfast=bacon%26eggs&2*2%3d5=false"));
            assertThat("Request.uri.path", req.getRequestURI().getPath(), is("/"));
            assertThat("Request.uri.rawQuery", req.getRequestURI().getRawQuery(), is("abc=x%20z&breakfast=bacon%26eggs&2*2%3d5=false"));
            assertThat("Request.uri.query", req.getRequestURI().getQuery(), is("abc=x z&breakfast=bacon&eggs&2*2=5=false"));
        }
    }
}
