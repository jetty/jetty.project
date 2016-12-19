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

package org.eclipse.jetty.websocket.jsr356;

import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import java.net.HttpCookie;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.websocket.ClientEndpointConfig;
import javax.websocket.ContainerProvider;
import javax.websocket.Endpoint;
import javax.websocket.EndpointConfig;
import javax.websocket.HandshakeResponse;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeRequest;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeResponse;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

public class CookiesTest
{
    private Server server;
    private ServerConnector connector;

    protected void startServer(Handler handler) throws Exception
    {
        server = new Server();
        connector = new ServerConnector(server);
        server.addConnector(connector);

        ContextHandler context = new ContextHandler();
        context.setContextPath("/");
        context.setHandler(handler);
        server.setHandler(context);

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
        
        startServer(new EchoHandler()
        {
            @Override
            public Object createWebSocket(ServletUpgradeRequest request, ServletUpgradeResponse response)
            {
                List<HttpCookie> cookies = request.getCookies();
                assertThat("Cookies", cookies, notNullValue());
                assertThat("Cookies", cookies.size(), is(1));
                HttpCookie cookie = cookies.get(0);
                Assert.assertEquals(cookieName, cookie.getName());
                Assert.assertEquals(cookieValue, cookie.getValue());

                Map<String, List<String>> headers = request.getHeaders();
                // Test case insensitivity
                Assert.assertTrue(headers.containsKey("cookie"));
                List<String> values = headers.get("Cookie");
                Assert.assertNotNull(values);
                Assert.assertEquals(1, values.size());
                Assert.assertEquals(cookieString, values.get(0));

                return super.createWebSocket(request, response);
            }
        });

        WebSocketContainer container = ContainerProvider.getWebSocketContainer();

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

        Endpoint endPoint = new Endpoint()
        {
            @Override
            public void onOpen(Session session, EndpointConfig config)
            {
            }
        };

        Session session = container.connectToServer(endPoint, config, URI.create("ws://localhost:" + connector.getLocalPort()));
        session.close();
    }

    @Test
    public void testCookiesAreSentToClient() throws Exception
    {
        final String cookieName = "name";
        final String cookieValue = "value";
        final String cookieDomain = "domain";
        final String cookiePath = "/path";
        startServer(new EchoHandler()
        {
            @Override
            public Object createWebSocket(ServletUpgradeRequest request, ServletUpgradeResponse response)
            {
                String cookieString = cookieName + "=" + cookieValue + ";Domain=" + cookieDomain + ";Path=" + cookiePath;
                response.getHeaders().put("Set-Cookie", Collections.singletonList(cookieString));
                return super.createWebSocket(request, response);
            }
        });

        WebSocketContainer container = ContainerProvider.getWebSocketContainer();

        ClientEndpointConfig.Builder builder = ClientEndpointConfig.Builder.create();
        builder.configurator(new ClientEndpointConfig.Configurator()
        {
            @Override
            public void afterResponse(HandshakeResponse response)
            {
                Map<String, List<String>> headers = response.getHeaders();
                // Test case insensitivity
                Assert.assertTrue(headers.containsKey("set-cookie"));
                List<String> values = headers.get("Set-Cookie");
                Assert.assertNotNull(values);
                Assert.assertEquals(1, values.size());

                List<HttpCookie> cookies = HttpCookie.parse(values.get(0));
                Assert.assertEquals(1, cookies.size());
                HttpCookie cookie = cookies.get(0);
                Assert.assertEquals(cookieName, cookie.getName());
                Assert.assertEquals(cookieValue, cookie.getValue());
                Assert.assertEquals(cookieDomain, cookie.getDomain());
                Assert.assertEquals(cookiePath, cookie.getPath());
            }
        });
        ClientEndpointConfig config = builder.build();

        Endpoint endPoint = new Endpoint()
        {
            @Override
            public void onOpen(Session session, EndpointConfig config)
            {
            }
        };

        Session session = container.connectToServer(endPoint, config, URI.create("ws://localhost:" + connector.getLocalPort()));
        session.close();
    }
}
