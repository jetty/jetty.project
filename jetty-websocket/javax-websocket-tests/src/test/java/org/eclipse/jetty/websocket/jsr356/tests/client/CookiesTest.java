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

package org.eclipse.jetty.websocket.jsr356.tests.client;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import java.net.HttpCookie;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.websocket.ClientEndpointConfig;
import javax.websocket.ContainerProvider;
import javax.websocket.Endpoint;
import javax.websocket.HandshakeResponse;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;

import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.server.Negotiation;
import org.eclipse.jetty.websocket.jsr356.tests.CoreServer;
import org.eclipse.jetty.websocket.jsr356.tests.DummyEndpoint;
import org.eclipse.jetty.websocket.jsr356.tests.framehandlers.StaticText;
import org.eclipse.jetty.websocket.jsr356.tests.framehandlers.WholeMessageEcho;
import org.junit.After;
import org.junit.Test;

public class CookiesTest
{
    private CoreServer server;

    protected void startServer(Function<Negotiation, FrameHandler> negotiationFunction) throws Exception
    {
        server = new CoreServer(negotiationFunction);
        server.start();
    }

    @After
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

        ClientEndpointConfig.Builder builder = ClientEndpointConfig.Builder.create();
        builder.configurator(new ClientEndpointConfig.Configurator()
        {
            @Override
            public void afterResponse(HandshakeResponse response)
            {
                Map<String, List<String>> headers = response.getHeaders();
                // Test case insensitivity
                assertTrue(headers.containsKey("set-cookie"));
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
        });
        ClientEndpointConfig config = builder.build();

        Endpoint endPoint = new DummyEndpoint();

        Session session = container.connectToServer(endPoint, config, server.getWsUri());
        session.close();
    }
}
