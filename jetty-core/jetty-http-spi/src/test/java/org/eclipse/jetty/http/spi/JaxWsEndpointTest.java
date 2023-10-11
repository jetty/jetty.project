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

package org.eclipse.jetty.http.spi;

import java.net.InetSocketAddress;
import java.net.ServerSocket;

import jakarta.jws.WebMethod;
import jakarta.jws.WebService;
import jakarta.xml.ws.Endpoint;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

public class JaxWsEndpointTest
{
    private static StacklessLogging STACKLESS_LOGGING;
    private static String URL_PREFIX;
    private static Server SERVER;
    private static HttpClient CLIENT;

    @Test
    public void testPostToURLWithoutTrailingSlash() throws Exception
    {
        Endpoint.publish(URL_PREFIX + "/add", new AddService());

        ContentResponse response = CLIENT.newRequest(URL_PREFIX)
            .method(HttpMethod.POST)
            .path("/add")
            .send();

        assertThat(response.getStatus(), is(500));
        assertThat(response.getContentAsString(), containsString("Couldn't create SOAP message due to exception"));
    }

    @WebService
    public static class AddService
    {
        @WebMethod
        public int add(int a, int b)
        {
            return a + b;
        }
    }

    @BeforeAll
    public static void setUp() throws Exception
    {
        LoggingUtil.init();
        STACKLESS_LOGGING = new StacklessLogging("com.sun.xml.ws.transport.http.HttpAdapter");

        int port;
        try (ServerSocket serverSocket = new ServerSocket())
        {
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress("localhost", 0));
            port = serverSocket.getLocalPort();
            URL_PREFIX = "http://localhost:" + port;
        }

        SERVER = new Server(new DelegatingThreadPool(new QueuedThreadPool()));
        ServerConnector connector = new ServerConnector(SERVER);
        connector.setPort(port);
        SERVER.addConnector(connector);
        SERVER.setHandler(new ContextHandlerCollection());
        SERVER.start();

        JettyHttpServerProvider.setServer(SERVER);
        System.setProperty("com.sun.net.httpserver.HttpServerProvider", JettyHttpServerProvider.class.getName());

        CLIENT = new HttpClient();
        CLIENT.start();
    }

    @AfterAll
    public static void tearDown()
    {
        LifeCycle.stop(CLIENT);
        LifeCycle.stop(SERVER);
        JettyHttpServerProvider.setServer(null);
        System.clearProperty("com.sun.net.httpserver.HttpServerProvider");
        STACKLESS_LOGGING.close();
    }
}
