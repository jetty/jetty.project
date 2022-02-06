//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.javax.tests.client;

import java.net.HttpCookie;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.websocket.ClientEndpointConfig;
import javax.websocket.ContainerProvider;
import javax.websocket.Endpoint;
import javax.websocket.HandshakeResponse;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;

import org.eclipse.jetty.util.FuturePromise;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.server.WebSocketNegotiation;
import org.eclipse.jetty.websocket.javax.tests.CoreServer;
import org.eclipse.jetty.websocket.javax.tests.DummyEndpoint;
import org.eclipse.jetty.websocket.javax.tests.framehandlers.StaticText;
import org.eclipse.jetty.websocket.javax.tests.framehandlers.WholeMessageEcho;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CookiesTest
{
    private CoreServer server;

    protected void startServer(Function<WebSocketNegotiation, FrameHandler> negotiationFunction) throws Exception
    {
        server = new CoreServer(negotiationFunction);
        server.start();
    }

    @AfterEach
    public void stopServer() throws Exception
    {
        server.stop();
    }

    @Test
    public void testCookiesAreSentToServer() throws Exception
    {
        final String cookieName = "name";
        final String cookieValue = "value";
        final String cookieString = cookieName + "=" + cookieValue;

        startServer(negotiation ->
        {
            HttpServletRequest request = negotiation.getRequest();
            Cookie[] cookies = request.getCookies();
            assertThat("Cookies", cookies, notNullValue());
            assertThat("Cookies", cookies.length, is(1));
            Cookie cookie = cookies[0];
            assertEquals(cookieName, cookie.getName());
            assertEquals(cookieValue, cookie.getValue());

            StringBuilder requestHeaders = new StringBuilder();
            Collections.list(request.getHeaderNames())
                .forEach(name -> requestHeaders.append(name).append(": ").append(request.getHeader(name)).append("\n"));

            return new StaticText(requestHeaders.toString());
        });

        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        server.addBean(container, true); // allow it to stop

        ClientEndpointConfig.Builder builder = ClientEndpointConfig.Builder.create();
        builder.configurator(new ClientEndpointConfig.Configurator()
        {
            @Override
            public void beforeRequest(Map<String, List<String>> headers)
            {
                headers.put("Cookie", Collections.singletonList(cookieString));
            }
        });

        ClientEndpointConfig config = builder.build();
        Endpoint endPoint = new DummyEndpoint();

        Session session = container.connectToServer(endPoint, config, server.getWsUri());
        session.close();
    }

    @Test
    public void testCookiesAreSentToClient() throws Exception
    {
        final String cookieName = "name";
        final String cookieValue = "value";
        final String cookieDomain = "domain";
        final String cookiePath = "/path";
        startServer(negotiation ->
        {
            Cookie cookie = new Cookie(cookieName, cookieValue);
            cookie.setDomain(cookieDomain);
            cookie.setPath(cookiePath);
            negotiation.getResponse().addCookie(cookie);
            return new WholeMessageEcho();
        });

        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        server.addBean(container); // allow it to stop

        FuturePromise<HandshakeResponse> handshakeResponseFuture = new FuturePromise<>();

        ClientEndpointConfig.Builder builder = ClientEndpointConfig.Builder.create();
        builder.configurator(new ClientEndpointConfig.Configurator()
        {
            @Override
            public void afterResponse(HandshakeResponse response)
            {
                handshakeResponseFuture.succeeded(response);
            }
        });
        ClientEndpointConfig config = builder.build();

        Endpoint endPoint = new DummyEndpoint();

        Session session = container.connectToServer(endPoint, config, server.getWsUri());

        // Wait for the handshake response
        try
        {
            HandshakeResponse response = handshakeResponseFuture.get(5, TimeUnit.SECONDS);
            Map<String, List<String>> headers = response.getHeaders();

            // Test case insensitivity
            assertTrue(headers.containsKey("Set-Cookie"));
            List<String> values = headers.get("Set-Cookie");
            assertNotNull(values);
            assertEquals(1, values.size());

            List<HttpCookie> cookies = HttpCookie.parse(values.get(0));
            assertEquals(1, cookies.size());
            HttpCookie cookie = cookies.get(0);
            assertEquals(cookieName, cookie.getName());
            assertEquals(cookieValue, cookie.getValue());
            assertEquals(cookieDomain, cookie.getDomain());
            assertEquals(cookiePath, cookie.getPath());
        }
        finally
        {
            session.close();
        }
    }
}
